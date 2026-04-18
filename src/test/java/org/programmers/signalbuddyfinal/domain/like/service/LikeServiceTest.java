package org.programmers.signalbuddyfinal.domain.like.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.LIKE_PENDING_KEY;
import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.generateKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.programmers.signalbuddyfinal.domain.crossroad.entity.Crossroad;
import org.programmers.signalbuddyfinal.domain.crossroad.repository.CrossroadRepository;
import org.programmers.signalbuddyfinal.domain.feedback.entity.Feedback;
import org.programmers.signalbuddyfinal.domain.feedback.entity.enums.FeedbackCategory;
import org.programmers.signalbuddyfinal.domain.feedback.repository.FeedbackRepository;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeExistResponse;
import org.programmers.signalbuddyfinal.domain.like.entity.Like;
import org.programmers.signalbuddyfinal.domain.like.exception.LikeErrorCode;
import org.programmers.signalbuddyfinal.domain.like.repository.LikeRepository;
import org.programmers.signalbuddyfinal.domain.member.entity.Member;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberRole;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberStatus;
import org.programmers.signalbuddyfinal.domain.member.fixture.TestMemberFactory;
import org.programmers.signalbuddyfinal.domain.member.repository.MemberRepository;
import org.programmers.signalbuddyfinal.global.dto.CustomUser2Member;
import org.programmers.signalbuddyfinal.global.exception.BusinessException;
import org.programmers.signalbuddyfinal.global.security.basic.CustomUserDetails;
import org.programmers.signalbuddyfinal.global.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class LikeServiceTest extends IntegrationTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CrossroadRepository crossroadRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Member feedbackWriter;
    private Feedback feedback;

    @BeforeEach
    void setup() {
        feedbackWriter = memberRepository.save(
            TestMemberFactory.createActiveUser("test@test.com", "tester")
        );

        Crossroad crossroad = Crossroad.create()
            .crossroadApiId("13214").name("00사거리")
            .lat(37.12222).lng(127.12132)
            .build();
        crossroad = crossroadRepository.save(crossroad);

        String subject = "test subject";
        String content = "test content";
        Feedback entity = Feedback.create()
            .subject(subject).content(content).secret(Boolean.FALSE)
            .category(FeedbackCategory.ETC)
            .member(feedbackWriter).crossroad(crossroad)
            .build();
        feedback = feedbackRepository.save(entity);
    }

    @AfterEach
    void tearDown() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory != null) {
            factory.getConnection().serverCommands().flushAll();
        }
    }

    @DisplayName("좋아요 추가를 성공한다.")
    @Test
    void addLike_Success() {
        // given
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        // when
        likeService.addLike(feedback.getFeedbackId(), user);

        // then
        String hashKey = generateKey(feedback.getFeedbackId(), feedbackWriter.getMemberId());
        String deleteLike = (String) redisTemplate.opsForHash().get(LIKE_PENDING_KEY, hashKey);
        assertThat(deleteLike).isEqualTo("ADD");
        redisTemplate.opsForHash().delete(LIKE_PENDING_KEY, hashKey);
    }

    @DisplayName("좋아요 추가를 실패한다.")
    @Test
    void addLike_Failure() {
        // given
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        likeRepository.save(Like.create(feedbackWriter, feedback));

        // when & then
        try {
            likeService.addLike(feedback.getFeedbackId(), user);
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(LikeErrorCode.ALREADY_ADDED_LIKE);
        }
    }

    @DisplayName("좋아요 취소를 성공한다.")
    @Test
    void deleteLike_Success() {
        // given
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        likeRepository.save(Like.create(feedbackWriter, feedback));

        // when
        likeService.deleteLike(feedback.getFeedbackId(), user);

        // then
        String hashKey = generateKey(feedback.getFeedbackId(), feedbackWriter.getMemberId());
        String deleteLike = (String) redisTemplate.opsForHash().get(LIKE_PENDING_KEY, hashKey);
        assertThat(deleteLike).isEqualTo("CANCEL");
        redisTemplate.opsForHash().delete(LIKE_PENDING_KEY, hashKey);
    }

    @DisplayName("좋아요 취소를 실패한다.")
    @Test
    void deleteLike_Failure() {
        // given
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        // when & then
        try {
            likeService.deleteLike(feedback.getFeedbackId(), user);
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(LikeErrorCode.NOT_FOUND_LIKE);
        }
    }

    @DisplayName("해당 좋아요가 존재한다.")
    @Test
    void existsLikeTrue() {
        // given
        Long feedbackId = feedback.getFeedbackId();
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        likeRepository.save(Like.create(feedbackWriter, feedback));

        // when
        LikeExistResponse actual = likeService.existsLike(feedbackId, user);

        // then
        assertThat(actual.getStatus()).isTrue();
    }

    @DisplayName("해당 좋아요가 존재하지 않는다.")
    @Test
    void existsLikeFalse() {
        // given
        Long feedbackId = feedback.getFeedbackId();
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        // when
        LikeExistResponse actual = likeService.existsLike(feedbackId, user);

        // then
        assertThat(actual.getStatus()).isFalse();
    }

    @DisplayName("Redis에 좋아요 추가 데이터가 임시 저장되어 있다.")
    @Test
    void existsLikeFromRedisTrue() {
        // given
        Long feedbackId = feedback.getFeedbackId();
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        likeService.addLike(feedbackId, user);

        // when
        LikeExistResponse actual = likeService.existsLike(feedbackId, user);

        // then
        assertThat(actual.getStatus()).isTrue();
    }

    @DisplayName("Redis에 좋아요 삭제 데이터가 임시 저장되어 있다.")
    @Test
    void existsLikeFromRedisFalse() {
        // given
        Long feedbackId = feedback.getFeedbackId();
        CustomUser2Member user = new CustomUser2Member(
            new CustomUserDetails(feedbackWriter.getMemberId(), "", "",
                "", "", MemberRole.USER, MemberStatus.ACTIVITY));

        likeRepository.save(Like.create(feedbackWriter, feedback));
        likeService.deleteLike(feedbackId, user);

        // when
        LikeExistResponse actual = likeService.existsLike(feedbackId, user);

        // then
        assertThat(actual.getStatus()).isFalse();
    }
}