package org.programmers.signalbuddyfinal.domain.like.performance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.programmers.signalbuddyfinal.global.db.RedisTestContainer;
import org.programmers.signalbuddyfinal.global.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis String vs Hash(pending/processing) 방식 성능 비교 테스트 (10회 반복 평균)
 *
 * <p>String 방식 (현재)
 * - 쓰기: SET like:{feedbackId}:{memberId} ADD/CANCEL
 * - 읽기: SCAN like:* → 키마다 GET (N+1 Redis 명령)
 * - 삭제: DEL key1 key2 ...
 *
 * <p>Hash 방식 (pending/processing 이중 버퍼)
 * - 쓰기: HSET like:pending {feedbackId}:{memberId} ADD/CANCEL
 * - 읽기: RENAME like:pending like:processing → HGETALL like:processing (2 Redis 명령)
 * - 삭제: DEL like:processing
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class LikeRedisPerformanceTest extends IntegrationTest implements RedisTestContainer {

    private static final int REPEAT = 10;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private static final String STRING_KEY_PREFIX = "like:";
    private static final String HASH_PENDING_KEY = "like:pending";
    private static final String HASH_PROCESSING_KEY = "like:processing";

    @AfterEach
    void tearDown() {
        flushAll();
    }

    // ===========================
    //  1,000건 비교
    // ===========================

    @DisplayName("[성능비교] 1,000건 - String 방식")
    @Test
    void performance_1000_string() {
        List<long[]> requests = generateRequests(1000, 10);
        runStringRepeat(requests, 1000);
    }

    @DisplayName("[성능비교] 1,000건 - Hash 방식 (pending/processing)")
    @Test
    void performance_1000_hash() {
        List<long[]> requests = generateRequests(1000, 10);
        runHashRepeat(requests, 1000);
    }

    // ===========================
    //  5,000건 비교
    // ===========================

    @DisplayName("[성능비교] 5,000건 - String 방식")
    @Test
    void performance_5000_string() {
        List<long[]> requests = generateRequests(5000, 50);
        runStringRepeat(requests, 5000);
    }

    @DisplayName("[성능비교] 5,000건 - Hash 방식 (pending/processing)")
    @Test
    void performance_5000_hash() {
        List<long[]> requests = generateRequests(5000, 50);
        runHashRepeat(requests, 5000);
    }

    // ===========================
    //  10,000건 비교
    // ===========================

    @DisplayName("[성능비교] 10,000건 - String 방식")
    @Test
    void performance_10000_string() {
        List<long[]> requests = generateRequests(10000, 100);
        runStringRepeat(requests, 10000);
    }

    @DisplayName("[성능비교] 10,000건 - Hash 방식 (pending/processing)")
    @Test
    void performance_10000_hash() {
        List<long[]> requests = generateRequests(10000, 100);
        runHashRepeat(requests, 10000);
    }

    // ===========================
    //  반복 실행 래퍼
    // ===========================

    private void runStringRepeat(List<long[]> requests, int totalCount) {
        long[] totalWriteNs = new long[REPEAT];
        long[] totalReadNs = new long[REPEAT];
        long[] totalDeleteNs = new long[REPEAT];
        long[] totalMemory = new long[REPEAT];

        for (int i = 0; i < REPEAT; i++) {
            flushAll();
            long[] result = runStringOnce(requests);
            totalWriteNs[i]  = result[0];
            totalReadNs[i]   = result[1];
            totalDeleteNs[i] = result[2];
            totalMemory[i]   = result[3];
        }

        printAverage(
            "String 방식", totalCount,
            totalWriteNs, totalReadNs, totalDeleteNs, totalMemory,
            "쓰기 (SET x" + totalCount + ")",
            "읽기 (SCAN + GET x" + totalCount + ")",
            "삭제 (DEL x" + totalCount + ")"
        );
    }

    private void runHashRepeat(List<long[]> requests, int totalCount) {
        long[] totalWriteNs = new long[REPEAT];
        long[] totalReadNs = new long[REPEAT];
        long[] totalDeleteNs = new long[REPEAT];
        long[] totalMemory = new long[REPEAT];

        for (int i = 0; i < REPEAT; i++) {
            flushAll();
            long[] result = runHashOnce(requests);
            totalWriteNs[i]  = result[0];
            totalReadNs[i]   = result[1];
            totalDeleteNs[i] = result[2];
            totalMemory[i]   = result[3];
        }

        printAverage("Hash 방식 (pending/processing)", totalCount,
            totalWriteNs, totalReadNs, totalDeleteNs, totalMemory,
            "쓰기 (HSET x" + totalCount + ")",
            "읽기 (RENAME + HGETALL)",
            "삭제 (DEL like:processing)");
    }

    // ===========================
    //  단일 실행 (측정값 반환)
    // ===========================

    /**
     * @return [writeNs, readNs, deleteNs, memoryBytes]
     */
    private long[] runStringOnce(List<long[]> requests) {
        // 쓰기: SET like:{feedbackId}:{memberId} ADD/CANCEL
        long writeStart = System.nanoTime();
        for (long[] req : requests) {
            String key = STRING_KEY_PREFIX + req[0] + ":" + req[1];
            String value = req[2] == 0 ? "ADD" : "CANCEL";
            redisTemplate.opsForValue().set(key, value);
        }
        long writeNs = System.nanoTime() - writeStart;

        long memoryBytes = getUsedMemoryBytes();

        // 읽기: SCAN like:* → 키마다 GET (N+1 패턴)
        List<String[]> readResult = new ArrayList<>();
        ScanOptions scanOptions = ScanOptions.scanOptions()
            .match(STRING_KEY_PREFIX + "*")
            .count(100)
            .build();

        long readStart = System.nanoTime();
        try (
            Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                connection -> connection.scan(scanOptions)
            )
        ) {

            while (cursor != null && cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                String[] parts = key.split(":");
                if (parts.length < 3) {
                    continue;
                }
                String value = redisTemplate.opsForValue().get(key);
                readResult.add(new String[]{parts[1], parts[2], value});
            }
        } catch (Exception e) {
            throw new RuntimeException("SCAN 중 오류 발생", e);
        }
        long readNs = System.nanoTime() - readStart;

        // 삭제: DEL key1 key2 ...
        List<String> keys = new ArrayList<>();
        for (String[] item : readResult) {
            keys.add(STRING_KEY_PREFIX + item[0] + ":" + item[1]);
        }

        long deleteStart = System.nanoTime();
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        long deleteNs = System.nanoTime() - deleteStart;

        return new long[]{ writeNs, readNs, deleteNs, memoryBytes };
    }

    /**
     * @return [writeNs, readNs, deleteNs, memoryBytes]
     */
    private long[] runHashOnce(List<long[]> requests) {
        // 쓰기: HSET like:pending {feedbackId}:{memberId} ADD/CANCEL
        long writeStart = System.nanoTime();
        for (long[] req : requests) {
            String field = req[0] + ":" + req[1];
            String value = req[2] == 0 ? "ADD" : "CANCEL";
            redisTemplate.opsForHash().put(HASH_PENDING_KEY, field, value);
        }
        long writeNs = System.nanoTime() - writeStart;

        long memoryBytes = getUsedMemoryBytes();

        // 읽기: RENAME like:pending → like:processing + HGETALL
        long readStart = System.nanoTime();
        redisTemplate.rename(HASH_PENDING_KEY, HASH_PROCESSING_KEY);
        Map<Object, Object> readResult = redisTemplate.opsForHash().entries(HASH_PROCESSING_KEY);
        long readNs = System.nanoTime() - readStart;

        // 삭제: DEL like:processing
        long deleteStart = System.nanoTime();
        redisTemplate.delete(HASH_PROCESSING_KEY);
        long deleteNs = System.nanoTime() - deleteStart;

        return new long[]{ writeNs, readNs, deleteNs, memoryBytes, readResult.size() };
    }

    // ===========================
    //  유틸리티
    // ===========================

    private List<long[]> generateRequests(int totalRequests, int feedbackCount) {
        List<long[]> requests = new ArrayList<>(totalRequests);
        int membersPerFeedback = totalRequests / feedbackCount;

        for (int f = 1; f <= feedbackCount; f++) {
            for (int m = 1; m <= membersPerFeedback; m++) {
                long type = (m <= membersPerFeedback * 0.7) ? 0 : 1; // 70% ADD, 30% CANCEL
                requests.add(new long[]{f, m, type});
            }
        }
        return requests;
    }

    private long getUsedMemoryBytes() {
        try {
            Properties info = redisConnectionFactory.getConnection()
                .serverCommands().info("memory");
            if (info != null) {
                String usedMemory = info.getProperty("used_memory");
                if (usedMemory != null) {
                    return Long.parseLong(usedMemory);
                }
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private void flushAll() {
        redisConnectionFactory.getConnection().serverCommands().flushAll();
    }

    private long avg(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private void printAverage(String label, int totalCount,
        long[] writeNs, long[] readNs, long[] deleteNs, long[] memoryBytes,
        String writeLabel, String readLabel, String deleteLabel) {

        long avgWrite  = avg(writeNs);
        long avgRead   = avg(readNs);
        long avgDelete = avg(deleteNs);
        long avgTotal  = avgWrite + avgRead + avgDelete;
        long avgMem    = avg(memoryBytes);

        System.out.println("\n" + "=".repeat(65));
        System.out.printf("  %s - %,d건 (%d회 평균)%n", label, totalCount, REPEAT);
        System.out.println("=".repeat(65));
        if (avgMem > 0) {
            System.out.printf("  쓰기 후 메모리 사용량 (평균): %,d bytes (%.2f KB)%n",
                avgMem, avgMem / 1024.0);
        }
        System.out.println("-".repeat(65));
        System.out.printf("  %-40s : %,d ms (%,d ns)%n",
            writeLabel, avgWrite / 1_000_000, avgWrite);
        System.out.printf("  %-40s : %,d ms (%,d ns)%n",
            readLabel, avgRead / 1_000_000, avgRead);
        System.out.printf("  %-40s : %,d ms (%,d ns)%n",
            deleteLabel, avgDelete / 1_000_000, avgDelete);
        System.out.println("-".repeat(65));
        System.out.printf("  평균 총 소요시간: %,d ms%n", avgTotal / 1_000_000);
        System.out.println("=".repeat(65));

        // 각 회차 원시 데이터 출력
        System.out.println("\n  [회차별 총 소요시간 (ms)]");
        for (int i = 0; i < REPEAT; i++) {
            long total = writeNs[i] + readNs[i] + deleteNs[i];
            System.out.printf("  %2d회: 쓰기 %,d ms | 읽기 %,d ms | 삭제 %,d ms | 합계 %,d ms%n",
                i + 1,
                writeNs[i] / 1_000_000,
                readNs[i] / 1_000_000,
                deleteNs[i] / 1_000_000,
                total / 1_000_000);
        }
        System.out.println();
    }
}
