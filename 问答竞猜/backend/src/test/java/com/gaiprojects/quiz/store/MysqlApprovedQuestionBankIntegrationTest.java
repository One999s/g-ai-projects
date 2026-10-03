package com.gaiprojects.quiz.store;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual MySQL content gate; only fresh generated schemas at the fixed local test endpoint. */
@EnabledIfEnvironmentVariable(named = "QUIZ_MYSQL_INTEGRATION", matches = "true")
class MysqlApprovedQuestionBankIntegrationTest extends ApprovedQuestionBankTest {
  private JdbcTemplate admin;
  private String database;

  @Override
  @BeforeEach
  void setup() throws Exception {
    String base = "jdbc:mysql://127.0.0.1:13306/", options = "?sslMode=REQUIRED&serverTimezone=UTC";
    String user = System.getenv().getOrDefault("QUIZ_IT_MYSQL_USER", "root"),
        password = System.getenv().getOrDefault("QUIZ_IT_MYSQL_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
    database = "quiz_bank_it_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    initialize(new DriverManagerDataSource(base + database + options, user, password), false);
  }

  @AfterEach
  void removeOwnedFixture() {
    if (admin != null && database != null && database.matches("quiz_bank_it_[a-f0-9]{32}"))
      admin.execute("DROP DATABASE " + database);
  }
}
