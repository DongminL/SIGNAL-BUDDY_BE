package org.programmers.signalbuddyfinal.global.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.programmers.signalbuddyfinal.global.config.DataInitializer;
import org.programmers.signalbuddyfinal.global.db.MariaDBTestContainer;
import org.programmers.signalbuddyfinal.global.db.RedisTestContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@SpringBootTest
@Import(DataInitializer.class)
@ExtendWith(SpringExtension.class)
public abstract class IntegrationTest implements MariaDBTestContainer, RedisTestContainer {

    @Autowired
    private DataInitializer dataInitializer;

    @BeforeEach
    void reset() {
        dataInitializer.clear();
        flushRedis();
    }
}
