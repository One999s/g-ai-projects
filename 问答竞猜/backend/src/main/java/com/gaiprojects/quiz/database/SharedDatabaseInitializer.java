package com.gaiprojects.quiz.database;

import java.util.*;
import org.springframework.context.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unsafe shared-db configuration before any datasource or schema initializer can run. */
public final class SharedDatabaseInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  public void initialize(ConfigurableApplicationContext context) {
    verify(context.getEnvironment());
  }

  public static void verify(ConfigurableEnvironment env) {
    var profiles = Arrays.asList(env.getActiveProfiles());
    if (!profiles.contains("shared-db")) return;
    SharedDataSourcePolicy.rejectUnsupportedOverrides(env);
    for (String key :
        List.of("SHARED_DATABASE_URL", "QUIZ_DATABASE_USER", "QUIZ_DATABASE_PASSWORD")) {
      String value = env.getProperty(key);
      if (value == null || value.isBlank() || value.contains("${")) fail();
    }
    if (profiles.contains("demo")
        || profiles.contains("mock")
        || !"never".equals(env.getProperty("spring.sql.init.mode"))
        || env.getProperty("spring.flyway.enabled", Boolean.class, true)
        || env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true)
        || !env.getProperty("spring.flyway.clean-disabled", Boolean.class, false)
        || !"quiz_schema_history".equals(env.getProperty("spring.flyway.table"))) fail();
    if (!env.getProperty("SHARED_DATABASE_URL").equals(env.getProperty("spring.datasource.url"))
        || !env.getProperty("QUIZ_DATABASE_USER")
            .equals(env.getProperty("spring.datasource.username"))
        || !env.getProperty("QUIZ_DATABASE_PASSWORD")
            .equals(env.getProperty("spring.datasource.password"))) fail();
  }

  private static void fail() {
    throw new IllegalStateException("SHARED_DATABASE_RUNTIME_CONFIGURATION_REQUIRED");
  }
}
