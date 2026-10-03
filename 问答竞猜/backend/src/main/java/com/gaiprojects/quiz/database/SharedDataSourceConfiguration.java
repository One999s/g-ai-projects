package com.gaiprojects.quiz.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.store.JdbcGameStore;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("shared-db")
public class SharedDataSourceConfiguration {
  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(ConfigurableEnvironment env) {
    return SharedDataSourcePolicy.create(env);
  }

  @Bean
  JdbcGameStore gameStore(DataSource ds, ObjectMapper mapper) {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    return new JdbcGameStore(new JdbcTemplate(ds), tx, mapper);
  }

  @Bean
  com.gaiprojects.quiz.store.ApprovedQuestionBank approvedQuestionBank(DataSource ds) {
    return new com.gaiprojects.quiz.store.ApprovedQuestionBank(new JdbcTemplate(ds));
  }

  @Bean
  static BeanPostProcessor sharedConnectionSafety(Environment env) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource ds)) return bean;
        try {
          if (!(bean instanceof HikariDataSource pool))
            throw new IllegalStateException("SHARED_DATABASE_DATASOURCE_CONFIGURATION_REQUIRED");
          SharedDataSourcePolicy.verify(pool, env);
          try (var c = ds.getConnection();
              var st = c.createStatement();
              var rows = st.executeQuery("SELECT DATABASE()")) {
            if (!rows.next()
                || !SharedDataSourcePolicy.selectedCatalog(env.getProperty("SHARED_DATABASE_URL"))
                    .equals(rows.getString(1)))
              throw new IllegalStateException("SHARED_DATABASE_CATALOG_MISMATCH");
          } catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("SHARED_DATABASE_CATALOG_UNAVAILABLE");
          }
          return bean;
        } catch (RuntimeException failure) {
          if (bean instanceof AutoCloseable owned) {
            try {
              owned.close();
            } catch (Exception ignored) {
            }
          }
          throw failure;
        }
      }
    };
  }
}
