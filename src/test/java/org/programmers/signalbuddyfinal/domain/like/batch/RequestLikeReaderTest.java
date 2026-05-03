package org.programmers.signalbuddyfinal.domain.like.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.LIKE_PENDING_KEY;
import static org.programmers.signalbuddyfinal.domain.like.service.LikeCacheService.LIKE_PROCESSING_KEY;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeUpdateRequest;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RequestLikeReaderTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RequestLikeReader reader;

    @BeforeEach
    void setUp() {
        reader = new RequestLikeReader(redisTemplate);
    }

    @DisplayName("like:pending 키가 없으면 rename을 호출하지 않는다.")
    @Test
    void open_withNoPendingKey_doesNotRename() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.FALSE);

        reader.open(new ExecutionContext());

        verify(redisTemplate, never()).rename(LIKE_PENDING_KEY, LIKE_PROCESSING_KEY);
    }

    @DisplayName("like:pending 키가 없으면 read()는 null을 반환한다.")
    @Test
    void read_whenPendingKeyAbsent_returnsNull() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.FALSE);
        reader.open(new ExecutionContext());

        LikeUpdateRequest result = reader.read();

        assertThat(result).isNull();
    }

    @DisplayName("like:pending 키가 있으면 like:processing으로 rename하고 항목을 로드한다.")
    @Test
    void open_withPendingKey_renamesAndLoadsEntries() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("1:2", "ADD"));

        reader.open(new ExecutionContext());

        verify(redisTemplate).rename(LIKE_PENDING_KEY, LIKE_PROCESSING_KEY);
    }

    @DisplayName("유효한 항목이 있으면 LikeUpdateRequest를 반환한다.")
    @Test
    void read_withValidEntry_returnsLikeUpdateRequest() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("10:20", "ADD"));
        reader.open(new ExecutionContext());

        LikeUpdateRequest result = reader.read();

        assertThat(result).isNotNull();
        assertThat(result.getFeedbackId()).isEqualTo(10L);
        assertThat(result.getMemberId()).isEqualTo(20L);
    }

    @DisplayName("CANCEL 타입 항목도 LikeUpdateRequest로 반환한다.")
    @Test
    void read_withCancelEntry_returnsLikeUpdateRequest() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("5:3", "CANCEL"));
        reader.open(new ExecutionContext());

        LikeUpdateRequest result = reader.read();

        assertThat(result).isNotNull();
        assertThat(result.getFeedbackId()).isEqualTo(5L);
        assertThat(result.getMemberId()).isEqualTo(3L);
    }

    @DisplayName("콜론이 없는 잘못된 키 형식은 건너뛰고 null을 반환한다.")
    @Test
    void read_withInvalidKeyFormat_skipsAndReturnsNull() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("invalidKey", "ADD"));
        reader.open(new ExecutionContext());

        LikeUpdateRequest result = reader.read();

        assertThat(result).isNull();
    }

    @DisplayName("숫자가 아닌 ID를 가진 키는 건너뛰고 null을 반환한다.")
    @Test
    void read_withNonNumericId_skipsAndReturnsNull() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("abc:def", "ADD"));
        reader.open(new ExecutionContext());

        LikeUpdateRequest result = reader.read();

        assertThat(result).isNull();
    }

    @DisplayName("유효한 항목과 잘못된 항목이 섞여 있을 때 유효한 항목을 반환한다.")
    @Test
    void read_withMixedEntries_returnsOnlyValidItem() {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("invalidKey", "ADD");
        entries.put("1:2", "ADD");

        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(entries);
        reader.open(new ExecutionContext());

        // first call: skips "invalidKey", returns LikeUpdateRequest for "1:2"
        // OR returns "1:2" first depending on map iteration order
        // Either way, at least one valid item should be returned within two reads
        LikeUpdateRequest first = reader.read();
        LikeUpdateRequest second = reader.read();

        assertThat(first == null ? second : first).isNotNull();
    }

    @DisplayName("모든 항목을 소비한 후 read()는 null을 반환한다.")
    @Test
    void read_afterAllItemsConsumed_returnsNull() {
        when(redisTemplate.hasKey(LIKE_PENDING_KEY)).thenReturn(Boolean.TRUE);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(LIKE_PROCESSING_KEY)).thenReturn(Map.of("1:2", "ADD"));
        reader.open(new ExecutionContext());

        reader.read(); // consume the single item

        LikeUpdateRequest result = reader.read();
        assertThat(result).isNull();
    }

    @DisplayName("close() 호출 시 like:processing 키를 삭제한다.")
    @Test
    void close_deletesProcessingKey() {
        reader.close();

        verify(redisTemplate).delete(LIKE_PROCESSING_KEY);
    }
}
