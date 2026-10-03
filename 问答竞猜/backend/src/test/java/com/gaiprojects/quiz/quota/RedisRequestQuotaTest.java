package com.gaiprojects.quiz.quota;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiprojects.quiz.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class RedisRequestQuotaTest {
  @Test
  void creationConsumesBothAtomicScopesWithServerGeneratedNonce() {
    List<String[]> seen = new ArrayList<>();
    var quota =
        new RedisRequestQuota(
            (script, keys, args) -> {
              assertTrue(script.contains("redis.call('TIME')"));
              seen.add(keys);
              seen.add(args);
              return 1L;
            });
    quota.check(new Player(2, 3), "/api/quiz/sessions");
    assertArrayEquals(
        new String[] {"quiz:quota:v1:{2:3}:all", "quiz:quota:v1:{2:3}:create"}, seen.get(0));
    assertEquals("120", seen.get(1)[1]);
    assertEquals("6", seen.get(1)[2]);
    assertDoesNotThrow(() -> UUID.fromString(seen.get(1)[0]));
  }

  @Test
  void normalRequestsCannotSplitLimitsByPathSessionOrRound() {
    List<String[]> keys = new ArrayList<>();
    var q =
        new RedisRequestQuota(
            (s, k, a) -> {
              keys.add(k);
              return 1L;
            });
    q.check(new Player(1, 2), "/api/quiz/me/progress");
    q.check(new Player(1, 2), "/api/quiz/sessions/anything/current");
    assertArrayEquals(keys.get(0), keys.get(1));
    assertEquals(1, keys.get(0).length);
  }

  @Test
  void identitiesAndSitesHaveSeparateBoundedKeys() {
    Set<String> keys = new HashSet<>();
    var q =
        new RedisRequestQuota(
            (s, k, a) -> {
              keys.add(k[0]);
              return 1L;
            });
    q.check(new Player(1, 2), "x");
    q.check(new Player(2, 2), "x");
    q.check(new Player(1, 3), "x");
    assertEquals(3, keys.size());
  }

  @Test
  void explicitDenialIs429() {
    var e =
        assertThrows(
            RuleException.class,
            () -> new RedisRequestQuota((s, k, a) -> 0L).check(new Player(1, 1), "x"));
    assertEquals(429, e.status);
    assertEquals("RATE_LIMITED", e.code);
  }

  @Test
  void everyUnknownReplyFailsClosed() {
    for (Long reply : Arrays.asList(null, -1L, 2L, Long.MAX_VALUE)) {
      var e =
          assertThrows(
              RuleException.class,
              () -> new RedisRequestQuota((s, k, a) -> reply).check(new Player(1, 1), "x"));
      assertEquals(503, e.status);
    }
  }

  @Test
  void errorNeverLeaksConnectionInformation() {
    var e =
        assertThrows(
            RuleException.class,
            () ->
                new RedisRequestQuota(
                        (s, k, a) -> {
                          throw new RuntimeException("secret-host-password");
                        })
                    .check(new Player(1, 1), "x"));
    assertEquals("RATE_LIMIT_UNAVAILABLE", e.getMessage());
    assertNull(e.getCause());
  }

  @Test
  void endpointRejectsMissingWeakAndUrlStyleSettings() {
    for (var e :
        List.of(
            new MockEnvironment(),
            new MockEnvironment()
                .withProperty("QUIZ_REDIS_HOST", "redis.example")
                .withProperty("QUIZ_REDIS_TLS", "false"),
            new MockEnvironment().withProperty("QUIZ_REDIS_HOST", "redis://host:6379"),
            new MockEnvironment()
                .withProperty("QUIZ_REDIS_HOST", "127.0.0.1")
                .withProperty("QUIZ_REDIS_TLS", "FALSE"),
            new MockEnvironment()
                .withProperty("QUIZ_REDIS_HOST", "127.0.0.1")
                .withProperty("QUIZ_REDIS_PORT", "0"))) {
      assertThrows(IllegalStateException.class, () -> RedisQuotaEndpoint.read(e));
    }
  }

  @Test
  void remoteRequiresTlsAndExplicitAclIdentity() {
    var env = new MockEnvironment().withProperty("QUIZ_REDIS_HOST", "redis.example");
    assertThrows(IllegalStateException.class, () -> RedisQuotaEndpoint.read(env));
    env.withProperty("QUIZ_REDIS_USERNAME", "quota-runtime")
        .withProperty("QUIZ_REDIS_PASSWORD", "synthetic-test-only");
    var uri = RedisQuotaEndpoint.read(env);
    assertTrue(uri.isSsl());
    assertTrue(uri.isVerifyPeer());
    assertEquals(0, uri.getDatabase());
    assertEquals(500, uri.getTimeout().toMillis());
  }

  @Test
  void arbitrarySpringRedisPropertiesNeverOverrideControlledEndpoint() {
    var env =
        new MockEnvironment()
            .withProperty("QUIZ_REDIS_HOST", "127.0.0.1")
            .withProperty("QUIZ_REDIS_TLS", "false")
            .withProperty("spring.data.redis.host", "foreign")
            .withProperty("spring.data.redis.database", "9");
    var uri = RedisQuotaEndpoint.read(env);
    assertEquals("127.0.0.1", uri.getHost());
    assertEquals(0, uri.getDatabase());
    assertFalse(uri.isSsl());
  }

  @Test
  void disabledConfigurationCreatesNoRuntimeOrConnection() {
    new ApplicationContextRunner()
        .withUserConfiguration(RedisQuotaConfiguration.class)
        .run(
            c -> {
              assertFalse(c.containsBean("quizQuotaConnection"));
              assertFalse(c.containsBean("quizRequestQuota"));
            });
  }

  @Test
  void enabledMisconfigurationCannotPublishAnAdmissionBean() {
    new ApplicationContextRunner()
        .withUserConfiguration(RedisQuotaConfiguration.class)
        .withPropertyValues("quiz.redis.enabled=true")
        .run(c -> assertNotNull(c.getStartupFailure()));
  }

  @Test
  void voiceAttemptsUseBoundSessionRoundScopeAndThreeLimit() {
    var seen = new ArrayList<String[]>();
    var q =
        new RedisRequestQuota(
            (script, keys, args) -> {
              seen.add(keys);
              seen.add(args);
              return 1L;
            });
    q.check(
        new Player(2, 3),
        "/api/quiz/sessions/00000000-0000-0000-0000-000000000001/rounds/00000000-0000-0000-0000-000000000002/voice-candidate");
    assertEquals(
        "quiz:quota:v1:{2:3}:voice:00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000002",
        seen.get(0)[1]);
    assertEquals("3", seen.get(1)[2]);
  }
}
