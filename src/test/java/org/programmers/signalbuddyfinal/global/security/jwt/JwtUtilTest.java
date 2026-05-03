package org.programmers.signalbuddyfinal.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.programmers.signalbuddyfinal.domain.member.entity.Member;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberRole;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberStatus;
import org.programmers.signalbuddyfinal.global.exception.BusinessException;
import org.programmers.signalbuddyfinal.global.security.basic.CustomUserDetails;
import org.programmers.signalbuddyfinal.global.security.basic.CustomUserDetailsService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class JwtUtilTest {

    private JwtUtil jwtUtil;
    private Authentication authentication;
    private String rawSecretKey = "mysupersecretkeyforjwtutiltestlongenoughenough";
    private String secretKey;
    private Key key;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    private CustomUserDetails customUserDetails;
    private Member member;
    private String stubAccessToken;
    private String stubRefreshToken;

    @BeforeEach
    void setUp() {
        secretKey = Base64.getEncoder().encodeToString(rawSecretKey.getBytes(StandardCharsets.UTF_8));
        key = Keys.hmacShaKeyFor(rawSecretKey.getBytes(StandardCharsets.UTF_8));

        member = Member.builder()
            .memberId(1l)
            .email("MembeForJwtUtil@test.com")
            .nickname("JwtUtil 테스트용")
            .profileImageUrl("프로필 경로")
            .role(MemberRole.USER)
            .memberStatus(MemberStatus.ACTIVITY)
            .build();

        customUserDetails = new CustomUserDetails(member);

        authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());

        jwtUtil = new JwtUtil(secretKey, 3*60*1000L,7*60*1000L, mock(RefreshTokenRepository.class),
            customUserDetailsService, redisTemplate);

        stubAccessToken = Jwts.builder()
            .subject(member.getMemberId().toString())
            .claim("status", member.getMemberStatus().name())
            .claim("auth", member.getRole().name())
            .issuedAt(new Date())
            .expiration(new Date(new Date().getTime() + 60*60*1000))
            .signWith(key)
            .compact();
    }

    @DisplayName("액세스 토큰 생성 성공")
    @Test
    void givenValidAuthentication_whenGenerateAccessToken_thenReturnAccessToken() {
        // when
        String token = jwtUtil.generateAccessToken(authentication);

        Claims claims = Jwts.parser()
            .verifyWith((SecretKey) key)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        // then
        assertThat(claims.getSubject()).isEqualTo(member.getMemberId().toString());
        assertThat(claims.get("status")).isEqualTo(member.getMemberStatus().name());
        assertThat(claims.get("auth")).isEqualTo(member.getRole().name());
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @DisplayName("리프레시 토큰 생성 성공")
    @Test
    void givenValidAuthentication_whenGenerateRefreshToken_thenReturnRefreshToken() {
        // when
        String token = jwtUtil.generateRefreshToken(authentication);

        Claims claims = Jwts.parser()
            .verifyWith((SecretKey) key)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        //then
        assertThat(claims.getSubject()).isEqualTo(member.getMemberId().toString());
        assertThat(claims.get("status")).isNull();
        assertThat(claims.get("auth")).isNull();
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @DisplayName("토큰에서 Claim 추출 성공")
    @Test
    void givenValidToken_whenParseToken_thenReturnClaims(){
        // when
        Claims actualClaim = jwtUtil.parseToken(stubAccessToken);

        // then
        assertThat(actualClaim.getSubject()).isEqualTo(member.getMemberId().toString());
        assertThat(actualClaim.get("status")).isEqualTo(member.getMemberStatus().name());
        assertThat(actualClaim.get("auth")).isEqualTo(member.getRole().name());
    }

    @DisplayName("토큰에서 Claim 추출 성공")
    @Test
    void givenValidToken_whenExtractClaimsOrThrow_thenReturnClaims(){
        // when
        Claims actualClaim = jwtUtil.extractClaimsOrThrow("accessToken", stubAccessToken);

        // then
        assertThat(actualClaim.getSubject()).isEqualTo(member.getMemberId().toString());
        assertThat(actualClaim.get("status")).isEqualTo(member.getMemberStatus().name());
        assertThat(actualClaim.get("auth")).isEqualTo(member.getRole().name());
    }

    @DisplayName("리프레시 토큰이 만료되어 예외 발생")
    @Test
    void givenExpiredRefreshToken_whenExtractClaimsOrThrow_thenThrowsExpiredRefreshToken() {
        // given
        stubRefreshToken = Jwts.builder()
            .subject("12")
            .issuedAt(new Date())
            .expiration(new Date(new Date().getTime() - 60*60*1000))
            .signWith(key)
            .compact();

        // when & then
        assertThatThrownBy(()->jwtUtil.extractClaimsOrThrow("refreshToken", stubRefreshToken))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(TokenErrorCode.EXPIRED_REFRESH_TOKEN.getMessage());
    }

    @DisplayName("액세스 토큰이 만료되어도 claim 추출에 성공한다.")
    @Test
    void givenExpiredAccessToken_whenExtractClaimsOrThrow_thenReturnClaims() {
        // given
        stubAccessToken = Jwts.builder()
            .subject("12")
            .claim("status", MemberStatus.ACTIVITY.name())
            .claim("auth", MemberRole.ADMIN.name())
            .issuedAt(new Date())
            .expiration(new Date(new Date().getTime() - 60*60*1000))
            .signWith(key)
            .compact();

        // when
        Claims actualClaim = jwtUtil.extractClaimsOrThrow("accessToken", stubAccessToken);

        // then
        assertThat(actualClaim.getSubject()).isEqualTo("12");
        assertThat(actualClaim.get("status")).isEqualTo(MemberStatus.ACTIVITY.name());
        assertThat(actualClaim.get("auth")).isEqualTo(MemberRole.ADMIN.name());
    }

    @DisplayName("잘못된 키로 서명된 토큰을 parse할 경우, JwtException이 발생한다.")
    @Test
    void givenTokenWithWrongSignature_whenExtractClaimsOrThrow_thenThrowsInvalidTokenError() {
        // given
        stubAccessToken = Jwts.builder()
            .subject("12")
            .claim("status", MemberStatus.ACTIVITY.name())
            .claim("auth", MemberRole.ADMIN.name())
            .issuedAt(new Date())
            .expiration(new Date(new Date().getTime() - 60*60*1000))
            .signWith(Keys.hmacShaKeyFor("thisiswrongkeyihavetomakealonglongkey".getBytes(StandardCharsets.UTF_8)))
            .compact();

        // when & then
        assertThatThrownBy(()->jwtUtil.extractClaimsOrThrow("accessToken", stubAccessToken))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(TokenErrorCode.INVALID_TOKEN.getMessage());
    }

    @DisplayName("잘못된 토큰을 parse할 경우, JwtException이 발생한다.")
    @Test
    void givenWrongToken_whenExtractClaimsOrThrow_thenThrowsInvalidToken() {
        // given
        String wrongToken = "jwtToken.wrong.token";

        // when & then
        assertThatThrownBy(()->jwtUtil.extractClaimsOrThrow("accessToken", wrongToken))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(TokenErrorCode.INVALID_TOKEN.getMessage());
    }

    @DisplayName("Bearer prefix를 성공적으로 제거한다.")
    @Test
    void givenAccessTokenWithPrefix_whenExtractAccessToken_thenAccessTokenWithoutPrefix(){
        // when
        String actualValue = jwtUtil.extractAccessToken("Bearer "+stubAccessToken);

        // then
        assertThat(actualValue).isEqualTo(stubAccessToken);
    }

    @DisplayName("prefix가 없어 예외가 발생한다.")
    @Test
    void givenAccessTokenWithoutPrefix_whenExtractAccessToken_thenThrowsAccessTokenNotExist(){
        // when & then
        assertThatThrownBy(()->jwtUtil.extractAccessToken(stubAccessToken))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(TokenErrorCode.ACCESS_TOKEN_NOT_EXIST.getMessage());
    }

    @DisplayName("prefix와 토큰 값이 없어 예외가 발생한다.")
    @Test
    void givenNull_whenExtractAccessToken_thenThrowsAccessTokenNotExist(){
        // when & then
        assertThatThrownBy(()->jwtUtil.extractAccessToken(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(TokenErrorCode.ACCESS_TOKEN_NOT_EXIST.getMessage());
    }

    @DisplayName("Authentication 객체를 추출한다.")
    @Test
    void givenToken_whenGetAuthentication_thenReturnAuthentication(){
        // given
        String token = jwtUtil.generateAccessToken(authentication);
        when(customUserDetailsService.loadUserByUsername(member.getMemberId().toString())).thenReturn(customUserDetails);

        // when
        Authentication actualAuthentication = jwtUtil.getAuthentication(token);

        // then
        CustomUserDetails principal = (CustomUserDetails) actualAuthentication.getPrincipal();
        assertThat(principal.getMemberId()).isEqualTo(member.getMemberId());
        verify(customUserDetailsService).loadUserByUsername(member.getMemberId().toString());
    }

    @DisplayName("블랙리스트에 액세스 토큰 추가한다.")
    @Test
    void givenAccessToken_whenAddBlackListExistingAccessToken_thenAddAccessTokenInBlacklist(){
        // given
        Date expiration = new Date(new Date().getTime() + 60*60*1000);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        jwtUtil.addBlackListExistingAccessToken(stubAccessToken, expiration);

        // then
        verify(valueOperations).set(
            eq("pending-blacklist:access-token:" + stubAccessToken),
            eq("pending"),
            eq(5l),
            eq(TimeUnit.MINUTES)
        );

        verify(valueOperations).set(
            eq("blacklist:access-token:" + stubAccessToken),
            eq(expiration.toString()),
            eq(Duration.between(new Date().toInstant(), expiration.toInstant()).getSeconds()),
            eq(TimeUnit.SECONDS)
        );
    }

    @DisplayName("accessToken이 pending 상태에 있어 false를 반환한다.")
    @Test
    void givenAccessTokenIsPending_whenCheckBlackList_thenReturnFalse(){
        // given
        when(redisTemplate.hasKey("pending-blacklist:access-token:" + stubAccessToken)).thenReturn(Boolean.TRUE);
        when(redisTemplate.hasKey("blacklist:access-token:" + stubAccessToken)).thenReturn(Boolean.TRUE);

        // when
        boolean actual = jwtUtil.checkBlacklist(stubAccessToken);

        // then
        assertThat(actual).isFalse();
    }

    @DisplayName("accessToken이 blackList에만 존재하여 true를 반환한다.")
    @Test
    void givenAccessTokenExistOnlyBlacklist_whenCheckBlacklist_thenReturnTrue(){
        // given
        when(redisTemplate.hasKey("pending-blacklist:access-token:" + stubAccessToken)).thenReturn(Boolean.FALSE);
        when(redisTemplate.hasKey("blacklist:access-token:" + stubAccessToken)).thenReturn(Boolean.TRUE);

        // when
        boolean actual = jwtUtil.checkBlacklist(stubAccessToken);

        // then
        assertThat(actual).isTrue();
    }

    @DisplayName("액세스 토큰의 유효기간이 남았으므로 blackList에 추가한다.")
    @Test
    void givenAccessTokenIsValid_whenValidateAccessTokenExpiration_thenAddBlackList(){
        // given
        JwtUtil spyJwtUtil = spy(new JwtUtil(secretKey,3*60*1000L,7*60*1000L, mock(RefreshTokenRepository.class),
            customUserDetailsService, redisTemplate));

        Claims claims = Jwts.parser()
            .verifyWith((SecretKey) key)
            .build()
            .parseClaimsJws(stubAccessToken).getPayload();

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        spyJwtUtil.validateAccessTokenExpiration(claims, stubAccessToken);

        // then
        verify(spyJwtUtil, times(1)).addBlackListExistingAccessToken(stubAccessToken, claims.getExpiration());

    }

    @DisplayName("액세스 토큰이 만료되었으므로 blackList에 추가하지 않는다.")
    @Test
    void givenAccessTokenIsExpired_whenValidateAccessTokenExpiration_thenDontAddBlackList(){
        JwtUtil spyJwtUtil = spy(new JwtUtil(secretKey,3*60*1000L,7*60*1000L, mock(RefreshTokenRepository.class),
            customUserDetailsService, redisTemplate));

        stubAccessToken = Jwts.builder()
            .subject("12")
            .claim("status", MemberStatus.ACTIVITY.name())
            .claim("auth", MemberRole.ADMIN.name())
            .issuedAt(new Date())
            .expiration(new Date(new Date().getTime() - 60*60*1000))
            .signWith(key)
            .compact();

        Claims claims = spyJwtUtil.extractClaimsOrThrow("accessToken", stubAccessToken);

        // when
        spyJwtUtil.validateAccessTokenExpiration(claims, stubAccessToken);

        // then
        verify(spyJwtUtil, times(0)).addBlackListExistingAccessToken(stubAccessToken, claims.getExpiration());
    }

}
