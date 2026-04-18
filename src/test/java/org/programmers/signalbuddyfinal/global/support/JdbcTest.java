package org.programmers.signalbuddyfinal.global.support;

import org.junit.jupiter.api.BeforeEach;
import org.programmers.signalbuddyfinal.global.config.DataInitializer;
import org.programmers.signalbuddyfinal.global.db.MariaDBTestContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@SpringBootTest
@Import(DataInitializer.class)
public abstract class JdbcTest implements MariaDBTestContainer {

    @Autowired
    private DataInitializer dataInitializer;

    @BeforeEach
    void reset() {
        dataInitializer.clear();
    }
}
