package com.gaiprojects.quiz.quota;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiprojects.quiz.core.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

/** Only the disposable loopback CI service. No configurable production destination or FLUSHDB. */
@EnabledIfEnvironmentVariable(named = "QUIZ_REDIS_INTEGRATION", matches = "true")
class RedisQuotaIntegrationTest {
  RedisClient adminClient;
  StatefulRedisConnection<String, String> admin;
  RedisQuotaConnection first, second;
  Player player;

  @BeforeEach
  void setup() {
    player = new Player(ThreadLocalRandom.current().nextInt(1, 1_000_000_000), 1);
    var uri = RedisURI.Builder.redis("127.0.0.1", 16379).withTimeout(Duration.ofSeconds(2)).build();
    adminClient = RedisClient.create(uri);
    admin = adminClient.connect();
    first = new RedisQuotaConnection(uri);
    second = new RedisQuotaConnection(uri);
  }

  String key(String suffix) {
    return "quiz:quota:v1:{" + player.siteId() + ":" + player.siteUserId() + "}:" + suffix;
  }

  @AfterEach
  void cleanup() {
    if (first != null) first.close();
    if (second != null) second.close();
    if (admin != null) {
      admin.sync().del(key("all"), key("create"));
      admin.close();
    }
    if (adminClient != null) adminClient.shutdown();
  }

  int status(RedisRequestQuota q, String path) {
    try {
      q.check(player, path);
      return 200;
    } catch (RuleException e) {
      return e.status;
    }
  }

  @Test
  void twoInstancesCannotOversellSixCreatesUnderParallelCalls() throws Exception {
    var a = new RedisRequestQuota(first);
    var b = new RedisRequestQuota(second);
    try (var workers = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 48; i++) {
        var q = i % 2 == 0 ? a : b;
        calls.add(() -> status(q, "/api/quiz/sessions"));
      }
      var outcomes = workers.invokeAll(calls);
      int accepted = 0;
      for (var f : outcomes) {
        int s = f.get();
        assertTrue(s == 200 || s == 429);
        if (s == 200) accepted++;
      }
      assertEquals(6, accepted);
    }
    assertEquals(6L, admin.sync().zcard(key("all")));
    assertEquals(6L, admin.sync().zcard(key("create")));
  }

  @Test
  void aggregateLimitIsSharedAcrossPathsAndInstances() {
    for (int i = 0; i < 120; i++)
      assertEquals(
          200, status(new RedisRequestQuota(i % 2 == 0 ? first : second), "/api/quiz/me/progress"));
    assertEquals(429, status(new RedisRequestQuota(second), "/api/quiz/other/current"));
    assertEquals(429, status(new RedisRequestQuota(first), "/api/quiz/sessions"));
    assertEquals(0L, admin.sync().exists(key("create")));
  }

  @Test
  void expiredEntriesArePrunedByRedisTimeAndKeysStayFinite() {
    var time = admin.sync().time();
    long now = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    for (int i = 0; i < 120; i++) admin.sync().zadd(key("all"), now - 61000, "old" + i);
    admin.sync().pexpire(key("all"), 65000);
    assertEquals(200, status(new RedisRequestQuota(first), "x"));
    assertEquals(1L, admin.sync().zcard(key("all")));
    long ttl = admin.sync().pttl(key("all"));
    assertTrue(ttl > 60000 && ttl <= 65000);
  }

  @Test
  void wrongTypeAndImmortalKeysFailClosed() {
    admin.sync().set(key("all"), "invalid");
    assertEquals(503, status(new RedisRequestQuota(first), "x"));
    admin.sync().del(key("all"));
    admin.sync().zadd(key("all"), 1, "old");
    assertEquals(503, status(new RedisRequestQuota(first), "x"));
  }

  @Test
  void corruptSecondScopeCannotPartiallyConsumeFirstScope() {
    admin.sync().set(key("create"), "invalid");
    assertEquals(503, status(new RedisRequestQuota(first), "/api/quiz/sessions"));
    assertEquals(0L, admin.sync().exists(key("all")));
  }

  @Test
  void everyDenialLeavesBoundedStorageAndNoNewNonce() {
    var q = new RedisRequestQuota(first);
    for (int i = 0; i < 6; i++) q.check(player, "/api/quiz/sessions");
    for (int i = 0; i < 100; i++) assertEquals(429, status(q, "/api/quiz/sessions"));
    assertEquals(6L, admin.sync().zcard(key("all")));
    assertEquals(6L, admin.sync().zcard(key("create")));
  }

  @Test
  void actualPausedRedisFailsClosedWithinFiniteCommandDeadline() throws Exception {
    admin.sync().clientPause(1200);
    long start = System.nanoTime();
    assertEquals(503, status(new RedisRequestQuota(first), "x"));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
    Thread.sleep(1300);
  }

  @Test
  void disconnectedConnectionHasNoPermissiveLocalFallback() {
    first.close();
    assertEquals(503, status(new RedisRequestQuota(first), "x"));
    first = null;
  }

  @Test
  void explicitRuntimeFactoryPublishesOnlyTheBoundedDistributedAdapter() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(RedisQuotaConfiguration.class)
        .withPropertyValues(
            "quiz.redis.enabled=true",
            "QUIZ_REDIS_HOST=127.0.0.1",
            "QUIZ_REDIS_PORT=16379",
            "QUIZ_REDIS_TLS=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var quota = context.getBean(com.gaiprojects.quiz.api.RequestQuota.class);
              assertInstanceOf(RedisRequestQuota.class, quota);
              quota.check(player, "x");
              assertEquals(1L, admin.sync().zcard(key("all")));
            });
  }

  @Test
  void scopedAclAllowsQuotaButDeniesOtherKeysAndAdministrativeWrites() {
    String user = "quiz_it_" + UUID.randomUUID().toString().replace("-", "");
    // This credential exists only in the temporary CI Redis and is deleted in finally.
    String pass = "quiz-ci-disposable-only";
    var acl = new AclSetuserArgs().reset().on().addPassword(pass).keyPattern("quiz:quota:v1:*");
    for (var command :
        List.of(
            CommandType.HELLO,
            CommandType.AUTH,
            CommandType.PING,
            CommandType.EVAL,
            CommandType.TIME,
            CommandType.TYPE,
            CommandType.PTTL,
            CommandType.ZREMRANGEBYSCORE,
            CommandType.ZCARD,
            CommandType.ZADD,
            CommandType.PEXPIRE)) acl.addCommand(command);
    acl.addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
        .addCommand(CommandType.CLIENT, CommandKeyword.SETNAME);
    admin.sync().aclSetuser(user, acl);
    var env =
        new MockEnvironment()
            .withProperty("QUIZ_REDIS_HOST", "127.0.0.1")
            .withProperty("QUIZ_REDIS_PORT", "16379")
            .withProperty("QUIZ_REDIS_TLS", "false")
            .withProperty("QUIZ_REDIS_USERNAME", user)
            .withProperty("QUIZ_REDIS_PASSWORD", pass);
    try (var scoped = new RedisQuotaConnection(RedisQuotaEndpoint.read(env))) {
      assertEquals(200, status(new RedisRequestQuota(scoped), "x"));
      assertThrows(
          RuntimeException.class,
          () ->
              scoped.eval(
                  "return redis.call('GET', KEYS[1])",
                  new String[] {"identity:users"},
                  new String[] {}));
      assertThrows(
          RuntimeException.class,
          () -> scoped.eval("return redis.call('FLUSHDB')", new String[] {}, new String[] {}));
      assertThrows(
          RuntimeException.class,
          () ->
              scoped.eval(
                  "return redis.call('SET', KEYS[1], 'x')",
                  new String[] {key("all")},
                  new String[] {}));
    } finally {
      admin.sync().aclDeluser(user);
    }
  }
}
