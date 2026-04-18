package org.programmers.signalbuddyfinal.domain.auth.service;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.programmers.signalbuddyfinal.global.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.thymeleaf.spring6.SpringTemplateEngine;

@EnableAsync
class EmailServiceTest extends IntegrationTest {

    @Autowired
    private EmailService emailService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private JavaMailSender javaMailSender;

    @Autowired
    SpringTemplateEngine springTemplateEngine;

    final String prefix = "auth:email:";

    @Test
    @DisplayName("이메일 전송에 성공한다.")
    void givenValidEmail_whenSendEmail_thenSendEmail() {
        // when
        emailService.sendEmail("test@test.com");

        // then
        await().atMost(10, TimeUnit.SECONDS)
            .pollDelay(2, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                boolean exists = redisTemplate.hasKey(prefix + "test@test.com");
                assertTrue(exists);
            });

        redisTemplate.delete(prefix + "test@test.com");
    }

}
