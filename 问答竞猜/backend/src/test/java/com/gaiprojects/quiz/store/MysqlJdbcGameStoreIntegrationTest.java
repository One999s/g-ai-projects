package com.gaiprojects.quiz.store;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in actual MySQL contract. The host/port cannot be overridden to a production destination. */
@EnabledIfEnvironmentVariable(named = "QUIZ_MYSQL_INTEGRATION", matches = "true")
class MysqlJdbcGameStoreIntegrationTest extends JdbcGameStoreTest {
  private JdbcTemplate admin;
  private String database;

  @Override
  @BeforeEach
  void setup() throws Exception {
    String base = "jdbc:mysql://127.0.0.1:13306/", options = "?sslMode=REQUIRED&serverTimezone=UTC";
    String user = System.getenv().getOrDefault("QUIZ_IT_MYSQL_USER", "root"),
        password = System.getenv().getOrDefault("QUIZ_IT_MYSQL_PASSWORD", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
    database = "quiz_it_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    initialize(new DriverManagerDataSource(base + database + options, user, password), false);
  }

  @Test
  void controlledSharedFactoryUsesLeastPrivilegeAndExactCatalog() throws Exception {
    String account = "qit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
        secret = UUID.randomUUID().toString();
    admin.execute("CREATE USER '" + account + "'@'%' IDENTIFIED BY '" + secret + "'");
    try {
      for (String[] grant :
          new String[][] {
            {"SELECT", "quiz_question_packs"},
            {"SELECT", "quiz_question_audit"},
            {"SELECT,INSERT,UPDATE", "quiz_sessions"},
            {"SELECT,INSERT", "quiz_scores"},
            {"SELECT,INSERT,UPDATE", "quiz_progress"},
            {"INSERT", "quiz_outbox"}
          })
        admin.execute(
            "GRANT " + grant[0] + " ON `" + database + "`.`" + grant[1] + "` TO '" + account
                + "'@'%'");
      var env =
          new org.springframework.mock.env.MockEnvironment()
              .withProperty(
                  "SHARED_DATABASE_URL",
                  "jdbc:mysql://127.0.0.1:13306/"
                      + database
                      + "?sslMode=REQUIRED&serverTimezone=UTC")
              .withProperty("QUIZ_DATABASE_USER", account)
              .withProperty("QUIZ_DATABASE_PASSWORD", secret)
              .withProperty("spring.datasource.url", "${SHARED_DATABASE_URL}")
              .withProperty("spring.datasource.username", "${QUIZ_DATABASE_USER}")
              .withProperty("spring.datasource.password", "${QUIZ_DATABASE_PASSWORD}")
              .withProperty("spring.sql.init.mode", "never")
              .withProperty("spring.flyway.enabled", "false")
              .withProperty("spring.flyway.table", "quiz_schema_history")
              .withProperty("spring.flyway.baseline-on-migrate", "false")
              .withProperty("spring.flyway.clean-disabled", "true");
      env.setActiveProfiles("shared-db");
      try (var context =
          new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
        context.setEnvironment(env);
        context.registerBean(
            com.fasterxml.jackson.databind.ObjectMapper.class,
            () -> new com.fasterxml.jackson.databind.ObjectMapper());
        context.register(com.gaiprojects.quiz.database.SharedDataSourceConfiguration.class);
        context.refresh();
        var runtime = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        org.junit.jupiter.api.Assertions.assertEquals(
            database, runtime.queryForObject("SELECT DATABASE()", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(
            1, runtime.queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
        var unavailable =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.gaiprojects.quiz.core.RuleException.class,
                () -> context.getBean(ApprovedQuestionBank.class).select("en", now));
        org.junit.jupiter.api.Assertions.assertEquals(
            "QUESTION_BANK_UNAVAILABLE", unavailable.code);
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> runtime.execute("CREATE TABLE forbidden(id INT)"));
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> runtime.execute("DELETE FROM quiz_sessions"));
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> runtime.queryForList("SELECT * FROM mysql.user"));
      }
    } finally {
      admin.execute("DROP USER IF EXISTS '" + account + "'@'%'");
    }
  }

  @AfterEach
  void removeOwnedFixture() {
    if (admin != null && database != null && database.matches("quiz_it_[a-f0-9]{32}"))
      admin.execute("DROP DATABASE " + database);
  }

  @Test
  void recordSnapshotDoesNotMixAConcurrentSettlementIntoHistory() throws Exception {
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    var inserted = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      var reader =
          new JdbcTemplate(db.getDataSource()) {
            @Override
            public <T> java.util.List<T> query(
                String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
              var result = super.query(sql, mapper, args);
              if (sql.contains("FROM quiz_progress") && inserted.compareAndSet(false, true)) {
                try {
                  pool.submit(() -> finish()).get(4, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }
              return result;
            }
          };
      var tx =
          new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                  db.getDataSource()));
      var consistent =
          new JdbcGameStore(reader, tx, new com.fasterxml.jackson.databind.ObjectMapper());
      var old = consistent.record(owner, () -> {});
      org.junit.jupiter.api.Assertions.assertTrue(inserted.get());
      org.junit.jupiter.api.Assertions.assertEquals(0, old.progress().gamesPlayed());
      org.junit.jupiter.api.Assertions.assertTrue(old.recent().isEmpty());
      var next = consistent.record(owner, () -> {});
      org.junit.jupiter.api.Assertions.assertEquals(1, next.progress().gamesPlayed());
      org.junit.jupiter.api.Assertions.assertEquals(1, next.recent().size());
      org.junit.jupiter.api.Assertions.assertEquals(750, next.progress().totalScore());
    } finally {
      pool.shutdownNow();
    }
  }
}
