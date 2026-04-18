package org.programmers.signalbuddyfinal.global.db;

import org.programmers.signalbuddyfinal.global.config.RedisConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Import(RedisConfig.class)
public interface RedisTestContainer {

    GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7.4.1-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    private static void registerRedisProperties(DynamicPropertyRegistry registry) {
        REDIS_CONTAINER.start();
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
    }

    default void flushRedis() {
        try {
            REDIS_CONTAINER.execInContainer("redis-cli", "FLUSHALL");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
