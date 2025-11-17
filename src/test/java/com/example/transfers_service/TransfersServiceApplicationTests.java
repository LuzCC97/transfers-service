package com.example.transfers_service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TransfersServiceApplicationTests {
    @Test
    void contextLoads() {
        // This test will verify that the application context loads
    }

    @Test
    void main_runs_without_web_server() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransfersServiceApplication.class)
                .withPropertyValues(
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
                )
                .run(context -> {
                    assertThat(context).isNotNull();
                    assertThat(context).hasNotFailed();
                });
    }
}