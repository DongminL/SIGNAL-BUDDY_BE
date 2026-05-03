package org.programmers.signalbuddyfinal.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class RedisConfigUnitTest {

    @Mock
    private RedisProperties redisProperties;

    @InjectMocks
    private RedisConfig redisConfig;

    @DisplayName("sentinel 설정이 null이면 standalone factory를 반환한다.")
    @Test
    void redisConnectionFactory_withNullSentinel_returnsStandaloneFactory() {
        when(redisProperties.getSentinel()).thenReturn(null);
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn(null);

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("sentinel master가 빈 문자열이면 standalone factory를 반환한다.")
    @Test
    void redisConnectionFactory_withEmptySentinelMaster_returnsStandaloneFactory() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("");
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn(null);

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("sentinel nodes가 null이면 standalone factory를 반환한다.")
    @Test
    void redisConnectionFactory_withNullSentinelNodes_returnsStandaloneFactory() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("mymaster");
        when(sentinelProps.getNodes()).thenReturn(null);
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn(null);

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("sentinel nodes가 빈 리스트면 standalone factory를 반환한다.")
    @Test
    void redisConnectionFactory_withEmptySentinelNodes_returnsStandaloneFactory() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("mymaster");
        when(sentinelProps.getNodes()).thenReturn(List.of());
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn(null);

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("유효한 sentinel 설정이 있으면 sentinel factory를 반환한다.")
    @Test
    void redisConnectionFactory_withValidSentinel_returnsSentinelFactory() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("mymaster");
        when(sentinelProps.getNodes()).thenReturn(List.of("sentinel1:26379"));
        when(redisProperties.getPassword()).thenReturn(null);
        when(sentinelProps.getPassword()).thenReturn(null);

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("standalone factory 빌드 시 비밀번호가 있으면 설정한다.")
    @Test
    void buildStandaloneFactory_withPassword_setsPassword() {
        when(redisProperties.getSentinel()).thenReturn(null);
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn("secret");

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("standalone factory 빌드 시 비밀번호가 빈 문자열이면 설정하지 않는다.")
    @Test
    void buildStandaloneFactory_withEmptyPassword_doesNotSetPassword() {
        when(redisProperties.getSentinel()).thenReturn(null);
        when(redisProperties.getHost()).thenReturn("localhost");
        when(redisProperties.getPort()).thenReturn(6379);
        when(redisProperties.getPassword()).thenReturn("");

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("sentinel factory 빌드 시 data 비밀번호와 sentinel 비밀번호를 모두 설정한다.")
    @Test
    void buildSentinelFactory_withBothPasswords_setsBothPasswords() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("mymaster");
        when(sentinelProps.getNodes()).thenReturn(List.of("sentinel1:26379"));
        when(redisProperties.getPassword()).thenReturn("dataPassword");
        when(sentinelProps.getPassword()).thenReturn("sentinelPassword");

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("sentinel factory 빌드 시 비밀번호가 빈 문자열이면 설정하지 않는다.")
    @Test
    void buildSentinelFactory_withEmptyPasswords_doesNotSetPasswords() {
        RedisProperties.Sentinel sentinelProps = mock(RedisProperties.Sentinel.class);
        when(redisProperties.getSentinel()).thenReturn(sentinelProps);
        when(sentinelProps.getMaster()).thenReturn("mymaster");
        when(sentinelProps.getNodes()).thenReturn(List.of("sentinel1:26379"));
        when(redisProperties.getPassword()).thenReturn("");
        when(sentinelProps.getPassword()).thenReturn("");

        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
    }

    @DisplayName("redisTemplate 빈을 올바르게 생성한다.")
    @Test
    void redisTemplate_createsTemplateWithConnectionFactory() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        RedisTemplate<Object, Object> template = redisConfig.redisTemplate(connectionFactory);

        assertThat(template).isNotNull();
        assertThat(template.getConnectionFactory()).isEqualTo(connectionFactory);
    }

    @DisplayName("transactionManager 빈으로 JpaTransactionManager를 반환한다.")
    @Test
    void transactionManager_returnsJpaTransactionManager() {
        PlatformTransactionManager manager = redisConfig.transactionManager();

        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    }
}
