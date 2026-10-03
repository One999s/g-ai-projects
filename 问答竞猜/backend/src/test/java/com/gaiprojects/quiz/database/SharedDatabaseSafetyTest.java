package com.gaiprojects.quiz.database;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SharedDatabaseSafetyTest {
  MockEnvironment safe() {
    var e =
        new MockEnvironment()
            .withProperty(
                "SHARED_DATABASE_URL",
                "jdbc:mysql://127.0.0.1/fixture?sslMode=VERIFY_IDENTITY&serverTimezone=UTC")
            .withProperty("QUIZ_DATABASE_USER", "fixture")
            .withProperty("QUIZ_DATABASE_PASSWORD", "test-only")
            .withProperty("spring.datasource.url", "${SHARED_DATABASE_URL}")
            .withProperty("spring.datasource.username", "${QUIZ_DATABASE_USER}")
            .withProperty("spring.datasource.password", "${QUIZ_DATABASE_PASSWORD}")
            .withProperty("spring.sql.init.mode", "never")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.flyway.table", "quiz_schema_history")
            .withProperty("spring.flyway.baseline-on-migrate", "false")
            .withProperty("spring.flyway.clean-disabled", "true");
    e.setActiveProfiles("shared-db");
    return e;
  }

  @Test
  void controlledFactoryIsLazyAndBounded() {
    try (var p = SharedDataSourcePolicy.create(safe())) {
      assertNull(p.getHikariPoolMXBean());
      assertEquals(4, p.getMaximumPoolSize());
      assertEquals(1, p.getMinimumIdle());
      SharedDataSourcePolicy.verify(p, safe());
    }
  }

  @Test
  void rejectsSqlHooksAndRelaxedHikariAliases() {
    for (String key :
        List.of(
            "spring.datasource.hikari.connectionInitSql",
            "spring.datasource.hikari.connection-test-query",
            "SPRING_DATASOURCE_HIKARI_CATALOG",
            "spring.datasource.hikari.jdbcUrl",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.jndi-name",
            "spring.datasource.type",
            "spring.datasource.hikari.data-source-properties.createDatabaseIfNotExist")) {
      var e = safe();
      e.setProperty(key, "unreviewed");
      assertThrows(IllegalStateException.class, () -> SharedDatabaseInitializer.verify(e), key);
    }
  }

  @Test
  void urlCannotCreateDatabaseOrWeakenTls() {
    for (String suffix :
        List.of(
            "createDatabaseIfNotExist=true",
            "allowLoadLocalInfile=true",
            "connectionLifecycleInterceptors=evil.Class",
            "user=other",
            "password=secret",
            "sslMode=DISABLED",
            "sslMode=REQUIRED")) {
      String url = "jdbc:mysql://127.0.0.1/fixture?sslMode=VERIFY_IDENTITY&" + suffix;
      assertThrows(IllegalStateException.class, () -> SharedDataSourcePolicy.selectedCatalog(url));
    }
  }

  @Test
  void malformedMultiHostUserinfoAndMissingTlsAreRejected() {
    for (String url :
        List.of(
            "jdbc:mysql://host/fixture",
            "jdbc:mysql://user:secret@host/fixture?sslMode=REQUIRED",
            "jdbc:mysql://host,other/fixture?sslMode=REQUIRED",
            "jdbc:mysql://host/?sslMode=REQUIRED",
            "jdbc:mysql://host/fixture?sslMode=REQUIRED#extra")) {
      assertThrows(IllegalStateException.class, () -> SharedDataSourcePolicy.selectedCatalog(url));
    }
  }

  @Test
  void effectivePoolCannotRedirectOrExecuteSqlBeforeFirstConnection() {
    List<Consumer<HikariConfig>> cases =
        List.of(
            p -> p.setCatalog("other"),
            p -> p.setSchema("other"),
            p -> p.setUsername("other"),
            p -> p.setPassword("other"),
            p -> p.setJdbcUrl("jdbc:mysql://host/other?sslMode=REQUIRED"),
            p -> p.setConnectionInitSql("CREATE TABLE bad(id INT)"),
            p -> p.setConnectionTestQuery("CREATE TABLE bad(id INT)"),
            p -> p.setDataSourceJNDI("java:other"),
            p -> p.addDataSourceProperty("databaseName", "other"));
    for (var change : cases) {
      try (var p = SharedDataSourcePolicy.create(safe())) {
        change.accept(p);
        assertThrows(IllegalStateException.class, () -> SharedDataSourcePolicy.verify(p, safe()));
        assertNull(p.getHikariPoolMXBean());
      }
    }
  }

  @Test
  void unsafeStartupOverridesAreRejected() {
    for (String[] pair :
        new String[][] {
          {"spring.sql.init.mode", "always"},
          {"spring.flyway.enabled", "true"},
          {"spring.flyway.baseline-on-migrate", "true"},
          {"spring.flyway.clean-disabled", "false"},
          {"spring.flyway.table", "flyway_schema_history"},
          {"spring.datasource.username", "other"}
        }) {
      var e = safe();
      e.setProperty(pair[0], pair[1]);
      assertThrows(IllegalStateException.class, () -> SharedDatabaseInitializer.verify(e));
    }
  }

  @Test
  void demoCannotCombineAndMissingCredentialsFail() {
    var e = safe();
    e.setActiveProfiles("shared-db", "demo");
    assertThrows(IllegalStateException.class, () -> SharedDatabaseInitializer.verify(e));
    for (String k :
        List.of("SHARED_DATABASE_URL", "QUIZ_DATABASE_USER", "QUIZ_DATABASE_PASSWORD")) {
      var env = safe();
      env.setProperty(k, "");
      assertThrows(IllegalStateException.class, () -> SharedDatabaseInitializer.verify(env));
    }
  }

  @Test
  void migrationBytesAndOwnedNamespaceRemainUnchanged() throws Exception {
    try (var in = getClass().getResourceAsStream("/db/migration/V001__quiz_business_tables.sql")) {
      byte[] b = in.readAllBytes();
      assertEquals(
          "9129c52f44988c69bc3f7f3e7524b249c8cceeb912950160cd06b989491883e7",
          HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)));
      String ddl =
          new String(b, java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
      int count = 0;
      for (String st : ddl.split(";"))
        if (!st.isBlank()) {
          assertTrue(st.stripLeading().startsWith("CREATE TABLE quiz_"));
          count++;
        }
      assertEquals(6, count);
    }
  }
}
