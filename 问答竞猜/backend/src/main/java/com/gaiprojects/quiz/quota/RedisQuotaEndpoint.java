package com.gaiprojects.quiz.quota;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.core.env.Environment;

/** No URL parser, arbitrary driver properties, TLS bypass or app-wide Redis autoconfiguration. */
final class RedisQuotaEndpoint {
  static RedisURI read(Environment env) {
    String host = env.getProperty("QUIZ_REDIS_HOST", "");
    String portText = env.getProperty("QUIZ_REDIS_PORT", "6379");
    String tlsText = env.getProperty("QUIZ_REDIS_TLS", "true");
    String user = env.getProperty("QUIZ_REDIS_USERNAME", "");
    String password = env.getProperty("QUIZ_REDIS_PASSWORD", "");
    if (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?") && !host.equals("::1"))
      fail();
    if (!portText.matches("[0-9]{1,5}") || !tlsText.matches("true|false")) fail();
    int port = Integer.parseInt(portText);
    if (port < 1 || port > 65535) fail();
    boolean tls = tlsText.equals("true"), loopback = host.equals("127.0.0.1") || host.equals("::1");
    if (!tls && !loopback) fail();
    if ((!loopback || tls) && (password.isBlank() || user.isBlank())) fail();
    if (user.length() > 128
        || password.length() > 4096
        || user.codePoints().anyMatch(Character::isISOControl)) fail();
    if (!user.isEmpty() && password.isEmpty()) fail();
    var builder =
        RedisURI.Builder.redis(host, port)
            .withSsl(tls)
            .withVerifyPeer(true)
            .withDatabase(0)
            .withTimeout(Duration.ofMillis(500));
    if (!password.isEmpty()) {
      if (user.isEmpty()) builder.withPassword(password.toCharArray());
      else builder.withAuthentication(user, password.toCharArray());
    }
    return builder.build();
  }

  private static void fail() {
    throw new IllegalStateException("Invalid explicit Quiz Redis configuration");
  }
}
