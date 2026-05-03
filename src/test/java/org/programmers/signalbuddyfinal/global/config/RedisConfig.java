package org.programmers.signalbuddyfinal.global.config;

import java.util.HashSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@TestConfiguration
@EnableTransactionManagement
public class RedisConfig {

    @Autowired
    private RedisProperties redisProperties;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisProperties.Sentinel sentinelProps = redisProperties.getSentinel();
        if (sentinelProps != null
            && sentinelProps.getMaster() != null && !sentinelProps.getMaster().isEmpty()
            && sentinelProps.getNodes() != null && !sentinelProps.getNodes().isEmpty()) {
            return buildSentinelFactory(sentinelProps);
        }
        return buildStandaloneFactory();
    }

    private LettuceConnectionFactory buildSentinelFactory(RedisProperties.Sentinel sentinelProps) {
        RedisSentinelConfiguration config = new RedisSentinelConfiguration(
            sentinelProps.getMaster(),
            new HashSet<>(sentinelProps.getNodes())
        );

        String dataPassword = redisProperties.getPassword();
        if (dataPassword != null && !dataPassword.isEmpty()) {
            config.setPassword(dataPassword);
        }

        String sentinelPassword = sentinelProps.getPassword();
        if (sentinelPassword != null && !sentinelPassword.isEmpty()) {
            config.setSentinelPassword(sentinelPassword);
        }

        return new LettuceConnectionFactory(config);
    }

    private LettuceConnectionFactory buildStandaloneFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisProperties.getHost());
        config.setPort(redisProperties.getPort());

        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }

        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<Object, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setEnableTransactionSupport(true);    // 트랜잭션 허용
        redisTemplate.setDefaultSerializer(new GenericJackson2JsonRedisSerializer());

        // Key Serializer: 문자열
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());

        // Value Serializer: JSON 직렬화
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        redisTemplate.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        return redisTemplate;
    }

    @Bean
    public PlatformTransactionManager transactionManager() {
        return new JpaTransactionManager();
    }
}
