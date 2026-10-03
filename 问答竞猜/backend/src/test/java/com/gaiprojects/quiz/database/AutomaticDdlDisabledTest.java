package com.gaiprojects.quiz.database;

import static org.junit.jupiter.api.Assertions.*;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootTest(
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:forbidden-startup.sql"
    })
@Import(AutomaticDdlDisabledTest.LocalFixture.class)
class AutomaticDdlDisabledTest {
  @TestConfiguration
  static class LocalFixture {
    @Bean
    DataSource fixtureDataSource() {
      return new DriverManagerDataSource("jdbc:h2:mem:no_auto_ddl;DB_CLOSE_DELAY=-1", "sa", "");
    }
  }

  @Autowired DataSource dataSource;

  @Test
  void evenExplicitStartupSqlConfigurationCannotExecuteDdl() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> new JdbcTemplate(dataSource).queryForList("SELECT * FROM must_not_run"));
  }
}
