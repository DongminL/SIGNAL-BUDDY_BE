package org.programmers.signalbuddyfinal.global.support;

import org.junit.jupiter.api.BeforeEach;
import org.programmers.signalbuddyfinal.global.config.DataInitializer;
import org.programmers.signalbuddyfinal.global.config.JpaAuditingConfig;
import org.programmers.signalbuddyfinal.global.config.TestQuerydslConfig;
import org.programmers.signalbuddyfinal.global.db.MariaDBTestContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({TestQuerydslConfig.class, DataInitializer.class, JpaAuditingConfig.class})
public abstract class RepositoryTest implements MariaDBTestContainer {

    @Autowired
    private DataInitializer dataInitializer;

    @BeforeEach
    void reset() {
        dataInitializer.clear();
    }
}
