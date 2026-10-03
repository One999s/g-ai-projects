package com.gaiprojects.quiz.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.env.*;

/** A small explicit connector configuration, not arbitrary Spring/Hikari/JDBC settings. */
public final class SharedDataSourcePolicy {
  private SharedDataSourcePolicy() {}

  private static final Set<String> SPRING_FIELDS =
      Set.of(
          "springdatasourceurl",
          "springdatasourceusername",
          "springdatasourcepassword",
          "springdatasourcehikarimaximumpoolsize",
          "springdatasourcehikariminimumidle",
          "springdatasourcehikariconnectiontimeout");

  public static void rejectUnsupportedOverrides(ConfigurableEnvironment env) {
    for (var source : env.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
      for (String raw : enumerable.getPropertyNames()) {
        String key = raw.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (key.startsWith("springdatasource") && !SPRING_FIELDS.contains(key)) fail();
      }
    }
    selectedCatalog(env.getProperty("SHARED_DATABASE_URL"));
  }

  public static String selectedCatalog(String url) {
    try {
      if (url == null
          || !url.startsWith("jdbc:mysql://")
          || url.length() > 2048
          || url.codePoints().anyMatch(Character::isISOControl)) fail();
      URI uri = URI.create(url.substring(5));
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) fail();
      String path = uri.getPath();
      if (path == null || !path.matches("/[A-Za-z0-9_-]{1,64}")) fail();
      Map<String, String> options = new HashMap<>();
      if (uri.getRawQuery() != null)
        for (String item : uri.getRawQuery().split("&", -1)) {
          String[] pair = item.split("=", 2);
          if (pair.length != 2) fail();
          String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
          String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
          if (options.putIfAbsent(key, value) != null) fail();
          boolean allowed =
              switch (key) {
                case "sslMode" ->
                    Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(value);
                case "serverTimezone", "connectionTimeZone" -> "UTC".equals(value);
                case "useUnicode", "tcpKeepAlive" -> "true".equals(value);
                case "characterEncoding" -> Set.of("UTF-8", "utf8").contains(value);
                case "autoReconnect", "allowPublicKeyRetrieval" -> "false".equals(value);
                case "connectTimeout" -> bounded(value, 100, 30000);
                case "socketTimeout" -> bounded(value, 1000, 60000);
                default -> false;
              };
          if (!allowed) fail();
        }
      if (!options.containsKey("sslMode")) fail();
      return path.substring(1);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("SHARED_DATABASE_DATASOURCE_CONFIGURATION_REQUIRED");
    }
  }

  private static boolean bounded(String value, int minimum, int maximum) {
    if (!value.matches("[0-9]{1,5}")) return false;
    int n = Integer.parseInt(value);
    return n >= minimum && n <= maximum;
  }

  public static HikariDataSource create(ConfigurableEnvironment env) {
    SharedDatabaseInitializer.verify(env);
    var dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(env.getRequiredProperty("SHARED_DATABASE_URL"));
    dataSource.setUsername(env.getRequiredProperty("QUIZ_DATABASE_USER"));
    dataSource.setPassword(env.getRequiredProperty("QUIZ_DATABASE_PASSWORD"));
    int maximum = env.getProperty("QUIZ_DB_POOL_SIZE", Integer.class, 4);
    if (maximum < 1 || maximum > 32) fail();
    dataSource.setMaximumPoolSize(maximum);
    dataSource.setMinimumIdle(1);
    dataSource.setConnectionTimeout(3000);
    dataSource.setPoolName("quiz-shared-runtime");
    verify(dataSource, env);
    return dataSource; // Deliberately lazy; the policy has run before the first connection.
  }

  public static void verify(HikariConfig pool, Environment env) {
    selectedCatalog(pool.getJdbcUrl());
    String selectedUser = env.getProperty("QUIZ_DATABASE_USER"),
        selectedPassword = env.getProperty("QUIZ_DATABASE_PASSWORD");
    if (selectedUser == null
        || selectedUser.isBlank()
        || selectedPassword == null
        || selectedPassword.isBlank()) fail();
    if (!Objects.equals(pool.getJdbcUrl(), env.getProperty("SHARED_DATABASE_URL"))
        || !Objects.equals(pool.getUsername(), env.getProperty("QUIZ_DATABASE_USER"))
        || !Objects.equals(pool.getPassword(), env.getProperty("QUIZ_DATABASE_PASSWORD"))
        || pool.getCatalog() != null
        || pool.getSchema() != null
        || pool.getConnectionInitSql() != null
        || pool.getConnectionTestQuery() != null
        || pool.getDataSource() != null
        || pool.getDataSourceClassName() != null
        || pool.getDataSourceJNDI() != null
        || !pool.getDataSourceProperties().isEmpty()
        || pool.getDriverClassName() != null
            && !"com.mysql.cj.jdbc.Driver".equals(pool.getDriverClassName())) fail();
  }

  private static void fail() {
    throw new IllegalStateException("SHARED_DATABASE_DATASOURCE_CONFIGURATION_REQUIRED");
  }
}
