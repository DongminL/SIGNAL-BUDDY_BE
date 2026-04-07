package org.programmers.signalbuddyfinal.domain.like.service;

import lombok.RequiredArgsConstructor;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeRequestType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LikeCacheService {

    private final StringRedisTemplate redisTemplate;

    public static final String LIKE_PENDING_KEY = "like:pending";
    public static final String LIKE_PROCESSING_KEY = "like:processing";

    public void addLike(String hashKey) {
        redisTemplate.opsForHash().put(LIKE_PENDING_KEY, hashKey, LikeRequestType.ADD.name());
    }

    public void cancelLike(String hashKey) {
        redisTemplate.opsForHash().put(LIKE_PENDING_KEY, hashKey, LikeRequestType.CANCEL.name());
    }

    public boolean exists(String hashKey) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(LIKE_PENDING_KEY, hashKey));
    }

    public void delete(String hashKey) {
        redisTemplate.opsForHash().delete(LIKE_PENDING_KEY, hashKey);
    }

    @Nullable
    public String getLikeType(String hashKey) {
        Object value = redisTemplate.opsForHash().get(LIKE_PENDING_KEY, hashKey);
        if (value == null) {
            value = redisTemplate.opsForHash().get(LIKE_PROCESSING_KEY, hashKey);
        }
        return value != null ? value.toString() : null;
    }

    public static String generateKey(Long feedbackId, Long memberId) {
        return feedbackId + ":" + memberId;
    }

    @Nullable
    public Boolean resolveCachedLikeState(String value) {
        if (LikeRequestType.ADD.name().equals(value)) {
            return Boolean.TRUE;
        }
        if (LikeRequestType.CANCEL.name().equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
