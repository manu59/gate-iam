package fr.gate.iam.backend.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DataSource dataSource;

    @Test
    void all_flyway_migrations_apply_cleanly() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")) {
            rs.next();
            int appliedMigrations = rs.getInt(1);
            assertThat(appliedMigrations)
                    .as("At least V001 migration must have been applied successfully")
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
