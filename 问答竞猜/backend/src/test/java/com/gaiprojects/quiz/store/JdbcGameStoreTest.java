package com.gaiprojects.quiz.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcGameStoreTest {
  JdbcTemplate db;
  JdbcGameStore store;
  GameRules rules;
  GameSession s;
  Player owner = new Player(7, 42);
  long now = 100000;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:quiz"
                + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            "sa",
            "");
    initialize(ds, true);
  }

  void initialize(javax.sql.DataSource ds, boolean h2) throws Exception {
    db = new JdbcTemplate(ds);
    String ddl;
    try (var input =
        getClass().getResourceAsStream("/db/migration/V001__quiz_business_tables.sql")) {
      ddl = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    ddl = ddl.replaceAll("(?m)^--.*$", "");
    if (h2) ddl = ddl.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
    for (String part : ddl.split(";")) if (!part.isBlank()) db.execute(part);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    store = new JdbcGameStore(db, tx, new ObjectMapper());
    rules = new GameRules(new Random(5));
    s = store.create(newSession(owner), "creation-key");
  }

  GameSession newSession(Player p) {
    return rules.create(
        p,
        "en",
        "TEST_ONLY",
        java.util.stream.IntStream.range(0, 5)
            .mapToObj(
                i ->
                    new Question(
                        "q" + i,
                        "en",
                        "Question",
                        List.of("A", "B", "C", "D"),
                        2,
                        "Explanation",
                        1000))
            .toList(),
        now);
  }

  String round() {
    return store.transact(s.id, owner, x -> x.roundIds.get(x.index));
  }

  void ready() {
    String r = round();
    store.transact(
        s.id,
        owner,
        x -> {
          rules.ready(x, r, now);
          return null;
        });
  }

  void finish() {
    for (int i = 0; i < 5; i++) {
      String r = round();
      String key = "answer-key-" + i;
      store.transact(
          s.id,
          owner,
          x -> {
            rules.ready(x, r, now);
            rules.answer(x, r, 2, key, now + 1000);
            if (x.index < 4) rules.next(x, r, now + 1001);
            return null;
          });
      now += 10000;
    }
  }

  @Test
  void sameOwnerCreationIsIdempotentAndOtherScopesAreIndependent() {
    assertEquals(s.id, store.create(newSession(owner), "creation-key").id);
    assertNotEquals(s.id, store.create(newSession(new Player(8, 42)), "creation-key").id);
    assertNotEquals(s.id, store.create(newSession(new Player(7, 43)), "creation-key").id);
    assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
  }

  @Test
  void bothUserAndSiteAreRequiredForReadsAndMutations() {
    for (Player p : List.of(new Player(8, 42), new Player(7, 43))) {
      var called = new java.util.concurrent.atomic.AtomicBoolean();
      var e =
          assertThrows(
              RuleException.class,
              () ->
                  store.transact(
                      s.id,
                      p,
                      x -> {
                        called.set(true);
                        return null;
                      }));
      assertEquals(404, e.status);
      assertFalse(called.get());
    }
  }

  @Test
  void finalAnswerCommitsScoreProgressOutboxWithoutNextClick() {
    finish();
    assertEquals(750, store.progress(owner).totalScore());
    assertEquals(1, store.progress(owner).gamesPlayed());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM quiz_scores", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM quiz_outbox", Integer.class));
    assertEquals("REVEALING", store.transact(s.id, owner, x -> x.phase));
    String r = round();
    store.transact(
        s.id,
        owner,
        x -> {
          rules.next(x, r, now);
          return null;
        });
    assertEquals(1, store.progress(owner).gamesPlayed());
  }

  @Test
  void finalAnswerRetriesSettleExactlyOnce() {
    finish();
    String r = round();
    for (int i = 0; i < 4; i++)
      store.transact(s.id, owner, x -> rules.answer(x, r, 2, "answer-key-4", now));
    assertEquals(1, store.progress(owner).gamesPlayed());
    assertEquals(750, store.progress(owner).totalScore());
  }

  @Test
  void historyAndProgressNeverCrossScope() {
    finish();
    assertEquals(1, store.archive(owner, 20).size());
    for (Player p : List.of(new Player(8, 42), new Player(7, 43))) {
      assertEquals(0, store.progress(p).gamesPlayed());
      assertTrue(store.archive(p, 20).isEmpty());
    }
  }

  @Test
  void timeoutConflictCommitsCanonicalTimeoutInsteadOfRollback() {
    ready();
    String r = round();
    var e =
        assertThrows(
            RuleException.class,
            () ->
                store.transact(
                    s.id, owner, x -> rules.answer(x, r, 2, "late-answer", now + 21000)));
    assertEquals("ANSWER_DEADLINE_PASSED", e.code);
    assertEquals("REVEALING", store.transact(s.id, owner, x -> x.phase));
    assertTrue(
        store.<Boolean>transact(s.id, owner, x -> x.results.get(0).timedOut()).booleanValue());
  }

  @Test
  void runtimeFailureRollsBackState() {
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transact(
                s.id,
                owner,
                x -> {
                  x.score = 999;
                  x.revision++;
                  throw new IllegalStateException("abort");
                }));
    assertEquals(0, store.<Integer>transact(s.id, owner, x -> x.score));
  }

  @Test
  void outboxFailureRollsBackWholeSettlement() {
    for (int i = 0; i < 4; i++) {
      String r = round();
      String key = "prepare-key-" + i;
      store.transact(
          s.id,
          owner,
          x -> {
            rules.ready(x, r, now);
            rules.answer(x, r, 2, key, now + 1000);
            rules.next(x, r, now + 1001);
            return null;
          });
      now += 10000;
    }
    ready();
    String r = round();
    db.execute("DROP TABLE quiz_outbox");
    assertThrows(
        RuntimeException.class,
        () -> store.transact(s.id, owner, x -> rules.answer(x, r, 2, "final-answer", now + 1000)));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM quiz_scores", Integer.class));
    assertEquals(0, store.progress(owner).gamesPlayed());
    assertEquals(4, store.<Integer>transact(s.id, owner, x -> x.results.size()));
  }

  @Test
  void concurrentFirstAnswersHaveOneWinner() throws Exception {
    ready();
    String r = round();
    var pool = Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<Callable<GameSession.Result>>();
      for (int i = 0; i < 16; i++) {
        int n = i;
        tasks.add(
            () ->
                store.transact(
                    s.id, owner, x -> rules.answer(x, r, n % 4, "parallel-key-" + n, now + 1000)));
      }
      var responses = pool.invokeAll(tasks);
      var first = responses.get(0).get();
      for (var response : responses) assertEquals(first, response.get());
      assertEquals(1, store.<Integer>transact(s.id, owner, x -> x.results.size()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void staleOrForeignSerializedIdentityIsRejectedWithoutRewrite() {
    String before =
        db.queryForObject(
            "SELECT state_json FROM quiz_sessions WHERE session_id=?", String.class, s.id);
    String forged = before.replace("\"siteUserId\":42", "\"siteUserId\":999");
    db.update("UPDATE quiz_sessions SET state_json=? WHERE session_id=?", forged, s.id);
    assertEquals(
        "QUIZ_STATE_VERSION_OR_INTEGRITY_INVALID",
        assertThrows(RuleException.class, () -> store.transact(s.id, owner, x -> x.score)).code);
    assertEquals(
        forged,
        db.queryForObject(
            "SELECT state_json FROM quiz_sessions WHERE session_id=?", String.class, s.id));
  }

  @Test
  void oldUnversionedStateIsNotSilentlyAdopted() {
    db.update(
        "UPDATE quiz_sessions SET state_json=? WHERE session_id=?", "{\"legacy\":true}", s.id);
    assertThrows(RuleException.class, () -> store.transact(s.id, owner, x -> x.score));
    assertEquals(
        "{\"legacy\":true}",
        db.queryForObject(
            "SELECT state_json FROM quiz_sessions WHERE session_id=?", String.class, s.id));
  }

  @Test
  void malformedScoreMutationIsRolledBack() {
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transact(
                s.id,
                owner,
                x -> {
                  x.score = 700;
                  x.revision++;
                  return null;
                }));
    assertEquals(0, store.<Integer>transact(s.id, owner, x -> x.score));
  }

  @Test
  void completedResultDoesNotBecomeAbandonedAfterExpiry() {
    finish();
    store.transact(
        s.id,
        owner,
        x -> {
          rules.tick(x, x.expiresAt + 1);
          return null;
        });
    assertEquals("REVEALING", store.transact(s.id, owner, x -> x.phase));
    store.transact(
        s.id,
        owner,
        x -> {
          rules.abandon(x, x.expiresAt + 1);
          return null;
        });
    assertEquals("FINISHED", store.transact(s.id, owner, x -> x.phase));
    assertEquals(1, store.progress(owner).gamesPlayed());
  }

  @Test
  void archivePageSizeIsBounded() {
    assertThrows(RuleException.class, () -> store.archive(owner, 101));
    assertThrows(RuleException.class, () -> store.archive(owner, 0));
  }

  @Test
  void concurrentAtomicCreationReturnsOneSession() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      var calls = new ArrayList<Callable<String>>();
      for (int i = 0; i < 12; i++)
        calls.add(
            () ->
                store.createAtomic(
                        owner, "en", "atomic-create-key", () -> {}, () -> newSession(owner))
                    .id);
      var responses = pool.invokeAll(calls);
      String first = responses.get(0).get();
      for (var response : responses) assertEquals(first, response.get());
      assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void expiredFinalCreationAuthorizationRollsBackInsert() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        RuleException.class,
        () ->
            store.createAtomic(
                owner,
                "en",
                "expired-create-key",
                () -> {
                  if (calls.incrementAndGet() > 1)
                    throw new RuleException("AUTHENTICATION_REQUIRED", 401);
                },
                () -> newSession(owner)));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
  }

  @Test
  void postMutationAuthorizationFailureRollsBack() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    String round = round();
    assertThrows(
        RuleException.class,
        () ->
            store.transactGuarded(
                s.id,
                owner,
                () -> {
                  if (calls.incrementAndGet() > 1)
                    throw new RuleException("AUTHENTICATION_REQUIRED", 401);
                },
                x -> {
                  rules.ready(x, round, now);
                  return null;
                }));
    assertEquals("LOADING", store.transact(s.id, owner, x -> x.phase));
  }

  @Test
  void failedApprovedSelectionCreatesNoSession() {
    assertThrows(
        RuleException.class,
        () ->
            store.createAtomic(
                owner,
                "en",
                "no-approved-pack",
                () -> {},
                () -> {
                  throw new RuleException("QUESTION_BANK_UNAVAILABLE", 503);
                }));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
  }

  @Test
  void expiredReadyConflictPersistsClosureWithoutAnyScore() {
    String round = round();
    long deadline = store.transact(s.id, owner, x -> x.loadingDeadline);
    assertEquals(
        deadline,
        db.queryForObject(
                "SELECT next_deadline_ms FROM quiz_sessions WHERE session_id=?", Long.class, s.id)
            .longValue());
    assertThrows(
        RuleException.class,
        () ->
            store.transact(
                s.id,
                owner,
                x -> {
                  rules.ready(x, round, deadline);
                  return null;
                }));
    assertEquals("ABANDONED", store.transact(s.id, owner, x -> x.phase));
    assertEquals(0, store.progress(owner).gamesPlayed());
  }

  @Test
  void combinedRecordIsScopedAndTracksOnlyCompletedGames() {
    var empty = store.record(owner, () -> {});
    assertEquals(0, empty.progress().gamesPlayed());
    assertTrue(empty.recent().isEmpty());
    assertEquals(20, empty.limit());
    finish();
    var record = store.record(owner, () -> {});
    assertEquals(750, record.progress().totalScore());
    assertEquals(1, record.recent().size());
    assertEquals(s.id, record.recent().get(0).sessionId());
    for (var p :
        List.of(
            new Player(owner.siteId() + 1, owner.siteUserId()),
            new Player(owner.siteId(), owner.siteUserId() + 1))) {
      var other = store.record(p, () -> {});
      assertEquals(0, other.progress().gamesPlayed());
      assertTrue(other.recent().isEmpty());
    }
  }

  @Test
  void combinedRecordRevalidatesAndRejectsRevocationAfterReads() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var error =
        assertThrows(
            RuleException.class,
            () ->
                store.record(
                    owner,
                    () -> {
                      if (calls.incrementAndGet() == 2)
                        throw new RuleException("AUTHENTICATION_REQUIRED", 401);
                    }));
    assertEquals(401, error.status);
    assertEquals(2, calls.get());
    assertEquals(0, store.progress(owner).gamesPlayed());
  }

  @Test
  void combinedRecordUsesDedicatedReadOnlyRepeatableTransaction() {
    store.record(
        owner,
        () -> {
          assertTrue(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          assertTrue(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isCurrentTransactionReadOnly());
          assertEquals(
              java.sql.Connection.TRANSACTION_REPEATABLE_READ,
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .getCurrentTransactionIsolationLevel());
        });
    assertFalse(
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive());
  }
}
