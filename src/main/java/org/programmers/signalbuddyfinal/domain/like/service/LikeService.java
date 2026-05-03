package org.programmers.signalbuddyfinal.domain.like.service;

import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.generateKey;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeExistResponse;
import org.programmers.signalbuddyfinal.domain.like.exception.LikeErrorCode;
import org.programmers.signalbuddyfinal.domain.like.repository.LikeRepository;
import org.programmers.signalbuddyfinal.global.dto.CustomUser2Member;
import org.programmers.signalbuddyfinal.global.exception.BusinessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LikeService {

    private final LikeRepository likeRepository;
    private final LikeCacheService likeCacheService;

    @Transactional
    @Retryable(
        retryFor = RedisConnectionFailureException.class,
        maxAttempts = 5,
        backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    public void addLike(Long feedbackId, CustomUser2Member user) {
        String key = generateKey(feedbackId, user.getMemberId());

        // 삭제 요청 데이터가 Redis에 있을 때
        if (likeCacheService.exists(key)) {
            likeCacheService.delete(key);
            return;
        }

        boolean isExisted = likeRepository.existsByMemberAndFeedback(user.getMemberId(), feedbackId);
        if (isExisted) {
            throw new BusinessException(LikeErrorCode.ALREADY_ADDED_LIKE);
        }

        likeCacheService.addLike(key);
    }

    @Retryable(
        retryFor = RedisConnectionFailureException.class,
        maxAttempts = 5,
        backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    public LikeExistResponse existsLike(Long feedbackId, CustomUser2Member user) {
        String key = generateKey(feedbackId, user.getMemberId());
        String cachedValue = likeCacheService.getLikeType(key);

        return Optional.ofNullable(likeCacheService.resolveCachedLikeState(cachedValue))
            .map(LikeExistResponse::new)
            .orElseGet(() -> new LikeExistResponse(
                likeRepository.existsByMemberAndFeedback(user.getMemberId(), feedbackId)
            ));
    }

    @Transactional
    @Retryable(
        retryFor = RedisConnectionFailureException.class,
        maxAttempts = 5,
        backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    public void deleteLike(Long feedbackId, CustomUser2Member user) {
        String key = generateKey(feedbackId, user.getMemberId());

        // 좋아요 데이터가 아직 DB에 저장되지 않은 경우
        if (likeCacheService.exists(key)) {
            likeCacheService.delete(key);
            return;
        }

        boolean isExisted = likeRepository.existsByMemberAndFeedback(user.getMemberId(), feedbackId);
        if (!isExisted) {
            throw new BusinessException(LikeErrorCode.NOT_FOUND_LIKE);
        }

        likeCacheService.cancelLike(key);
    }
}