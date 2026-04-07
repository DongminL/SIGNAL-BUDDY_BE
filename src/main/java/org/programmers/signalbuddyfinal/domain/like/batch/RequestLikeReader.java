package org.programmers.signalbuddyfinal.domain.like.batch;

import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.LIKE_PENDING_KEY;
import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.LIKE_PROCESSING_KEY;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeUpdateRequest;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@RequiredArgsConstructor
public class RequestLikeReader implements ItemStreamReader<LikeUpdateRequest> {

    private final StringRedisTemplate redisTemplate;

    private Iterator<Map.Entry<Object, Object>> iterator = Collections.emptyIterator();

    @Override
    public void open(ExecutionContext executionContext) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(LIKE_PENDING_KEY))) {
            log.info("like:pending 키가 존재하지 않아 처리할 항목이 없습니다.");
            return;
        }

        // pending → processing 원자적 스왑 (배치 처리 중 신규 요청은 like:pending에 쌓임)
        redisTemplate.rename(LIKE_PENDING_KEY, LIKE_PROCESSING_KEY);

        Map<Object, Object> entries = redisTemplate.opsForHash().entries(LIKE_PROCESSING_KEY);
        iterator = entries.entrySet().iterator();
        log.info("like:processing에서 {}건 읽기 시작", entries.size());
    }

    @Override
    public LikeUpdateRequest read() {
        while (iterator.hasNext()) {
            Map.Entry<Object, Object> entry = iterator.next();
            String hashKey = entry.getKey().toString(); // {feedbackId}:{memberId}
            String[] parts = hashKey.split(":");
            if (parts.length < 2) {
                continue;
            }

            try {
                return LikeUpdateRequest.builder()
                    .feedbackId(Long.parseLong(parts[0]))
                    .memberId(Long.parseLong(parts[1]))
                    .likeRequestType(entry.getValue().toString())
                    .build();

            } catch (NumberFormatException e) {
                log.warn("like:processing 필드 파싱 실패: {}", hashKey);
            }
        }
        return null;
    }

    @Override
    public void close() {
        redisTemplate.delete(LIKE_PROCESSING_KEY);
    }
}
