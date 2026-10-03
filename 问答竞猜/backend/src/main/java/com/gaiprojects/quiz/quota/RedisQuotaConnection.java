package com.gaiprojects.quiz.quota;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** One bounded connection, finite command/connect deadlines, rejects new disconnected requests. */
final class RedisQuotaConnection implements AutoCloseable, RedisRequestQuota.Executor {
  private final DefaultClientResources resources;
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final Semaphore permits = new Semaphore(16);

  RedisQuotaConnection(RedisURI uri) {
    resources =
        DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    client = RedisClient.create(resources, uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .requestQueueSize(16)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
            .build());
    try {
      connection = client.connect();
    } catch (RuntimeException failure) {
      try {
        client.shutdown(0, 1, TimeUnit.SECONDS);
      } finally {
        resources.shutdown(0, 1, TimeUnit.SECONDS);
      }
      throw new IllegalStateException("Quiz Redis admission is unavailable");
    }
  }

  @Override
  public Long eval(String script, String[] keys, String[] arguments) {
    if (!permits.tryAcquire()) throw new IllegalStateException("Admission capacity unavailable");
    try {
      return connection.sync().eval(script, ScriptOutputType.INTEGER, keys, arguments);
    } finally {
      permits.release();
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } finally {
      try {
        client.shutdown(0, 1, TimeUnit.SECONDS);
      } finally {
        resources.shutdown(0, 1, TimeUnit.SECONDS);
      }
    }
  }
}
