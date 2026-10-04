package com.gaiprojects.quiz.store;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiprojects.quiz.content.*;
import com.gaiprojects.quiz.core.RuleException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class QuestionPackPublisherTest {
  DataSource source;
  JdbcTemplate db;
  QuestionPackPublisher publisher;
  ApprovedQuestionBankTest fixture;
  ContentAccess access = new ContentAccess("TEST_GLOBAL_REVIEWER", () -> {});

  @BeforeEach
  void setup() throws Exception {
    initialize(
        new DriverManagerDataSource(
            "jdbc:h2:mem:publish" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""),
        true);
  }

  void initialize(DataSource ds, boolean h2) throws Exception {
    source = ds;
    fixture = new ApprovedQuestionBankTest();
    fixture.initialize(ds, h2);
    db = fixture.db;
    publisher =
        new QuestionPackPublisher(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            fixture.bank,
            Clock.fixed(Instant.ofEpochMilli(ApprovedQuestionBankTest.NOW), ZoneOffset.UTC));
  }

  QuestionPackPublisher.Input input(String raw, String hash) {
    return new QuestionPackPublisher.Input(
        "publication-v1", "en", raw, hash, "Assistant-reviewed synthetic test only");
  }

  QuestionPackPublisher.Input ready() {
    var preview = publisher.preview(access, input(fixture.raw, null));
    return input(fixture.raw, preview.previewHash());
  }

  long count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void previewValidatesWithoutWritingOrReturningAnswers() {
    var p = publisher.preview(access, input(fixture.raw, null));
    assertEquals(8, p.questionCount());
    assertEquals(2, p.schemaVersion());
    assertEquals("AVAILABLE", p.versionState());
    assertEquals(0, count("quiz_question_packs"));
    assertEquals(0, count("quiz_question_audit"));
    assertFalse(p.toString().contains("EXPLANATION"));
  }

  @Test
  void publicationAndRetryAreImmutableAndAuditedOnce() {
    var request = ready();
    assertTrue(publisher.publish(access, request).created());
    assertFalse(publisher.publish(access, request).created());
    assertEquals("IDENTICAL", publisher.preview(access, request).versionState());
    assertEquals(1, count("quiz_question_packs"));
    assertEquals(1, count("quiz_question_audit"));
    assertEquals(
        "publication-v1", fixture.bank.select("en", ApprovedQuestionBankTest.NOW).version());
    assertEquals(
        "TEST_GLOBAL_REVIEWER",
        db.queryForObject("SELECT reviewer FROM quiz_question_packs", String.class));
  }

  @Test
  void publishedChapterPackStartsARealDurableSession() throws Exception {
    fixture.journeyPack();
    publisher.publish(access, ready());
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var store = new JdbcGameStore(db, tx, new com.fasterxml.jackson.databind.ObjectMapper());
    var clock = Clock.fixed(Instant.ofEpochMilli(ApprovedQuestionBankTest.NOW), ZoneOffset.UTC);
    var game =
        new com.gaiprojects.quiz.service.GameService(
            store, fixture.bank, new com.gaiprojects.quiz.core.GameRules(), clock);
    var player =
        new com.gaiprojects.quiz.identity.VerifiedAccess(
            new com.gaiprojects.quiz.core.Player(7, 42), () -> {});
    var session =
        game.create(player, "en", "imported-chapter", null, "publication-v1", "chapter-1");
    assertEquals("chapter-1", session.chapter().levelId());
    assertNull(session.question());
    assertEquals(1, count("quiz_sessions"));
    assertEquals(
        "publication-v1",
        db.queryForObject("SELECT bank_version FROM quiz_sessions", String.class));
  }

  @Test
  void changedBytesConflictEvenWhenSemanticallyEquivalent() {
    publisher.publish(access, ready());
    assertEquals(
        "PACK_VERSION_CONFLICT",
        assertThrows(
                RuleException.class,
                () -> publisher.preview(access, input(fixture.raw + " ", null)))
            .code);
    assertEquals(1, count("quiz_question_audit"));
  }

  @Test
  void changedPreviewIdentityOrBytesCannotPublish() {
    var request = ready();
    assertEquals(
        "PACK_PREVIEW_CHANGED",
        assertThrows(
                RuleException.class,
                () -> publisher.publish(access, input(fixture.raw + " ", request.previewHash())))
            .code);
    assertEquals(
        "PACK_PREVIEW_CHANGED",
        assertThrows(
                RuleException.class,
                () ->
                    publisher.publish(
                        access,
                        new QuestionPackPublisher.Input(
                            "another", "en", fixture.raw, request.previewHash(), "Review")))
            .code);
    assertEquals(0, count("quiz_question_packs"));
  }

  @Test
  void noReviewReasonNoPublication() {
    var request = ready();
    assertEquals(
        "PUBLICATION_REASON_REQUIRED",
        assertThrows(
                RuleException.class,
                () ->
                    publisher.publish(
                        access,
                        new QuestionPackPublisher.Input(
                            request.version(), "en", fixture.raw, request.previewHash(), " ")))
            .code);
    assertEquals(0, count("quiz_question_packs"));
  }

  @Test
  void auditFailureRollsBackPack() {
    var request = ready();
    db.execute(
        "ALTER TABLE quiz_question_audit ADD CONSTRAINT test_reject_audit CHECK (actor_reference <>"
            + " 'TEST_GLOBAL_REVIEWER')");
    assertThrows(RuntimeException.class, () -> publisher.publish(access, request));
    assertEquals(0, count("quiz_question_packs"));
    assertEquals(0, count("quiz_question_audit"));
  }

  @Test
  void revokedCapabilityAtCommitRollsBackBothRows() {
    var calls = new AtomicInteger();
    var expires =
        new ContentAccess(
            "TEST_GLOBAL_REVIEWER",
            () -> {
              if (calls.incrementAndGet() >= 3)
                throw new RuleException("CONTENT_PUBLICATION_FORBIDDEN", 403);
            });
    assertThrows(RuleException.class, () -> publisher.publish(expires, ready()));
    assertEquals(0, count("quiz_question_packs"));
    assertEquals(0, count("quiz_question_audit"));
  }

  @Test
  void malformedOrUnreviewedPacksFailTheSameGateAsGameplay() {
    for (String raw :
        List.of(
            "{}",
            fixture.raw + "{}",
            fixture.raw.replace("https://science.nasa.gov/", "http://example.org/"),
            fixture.raw.replace("TEST_REVIEWER", ""),
            fixture.raw.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2")))
      assertEquals(
          "QUESTION_PACK_INVALID",
          assertThrows(RuleException.class, () -> publisher.preview(access, input(raw, null)))
              .code);
    assertEquals(0, count("quiz_question_packs"));
  }

  @Test
  void invalidUnicodeCannotBeSilentlyChangedByTheDatabaseDriver() {
    String escaped = fixture.raw.replace("TEST ONLY QUESTION", "TEST " + "\\" + "uD800");
    String rawSurrogate = fixture.raw.replace("TEST ONLY QUESTION", "TEST " + (char) 0xd800);
    for (String raw : List.of(escaped, rawSurrogate))
      assertEquals(
          "QUESTION_PACK_INVALID",
          assertThrows(RuleException.class, () -> publisher.preview(access, input(raw, null)))
              .code);
    assertEquals(0, count("quiz_question_packs"));
  }

  @Test
  void invalidIdentityAndOversizedDocumentAreRejected() {
    assertThrows(
        RuleException.class,
        () ->
            publisher.preview(
                access, new QuestionPackPublisher.Input("a/b", "en", fixture.raw, null, null)));
    assertThrows(
        RuleException.class, () -> publisher.preview(access, input(" ".repeat(262145), null)));
    assertThrows(
        RuleException.class,
        () ->
            publisher.preview(
                access, new QuestionPackPublisher.Input("valid", "zz", fixture.raw, null, null)));
  }

  @Test
  void unauditedExistingVersionCannotBeAdopted() throws Exception {
    publisher.publish(access, ready());
    db.update("DELETE FROM quiz_question_audit");
    assertEquals(
        "PACK_VERSION_CONFLICT",
        assertThrows(RuleException.class, () -> publisher.preview(access, input(fixture.raw, null)))
            .code);
    assertEquals(0, count("quiz_question_audit"));
  }

  @Test
  void concurrentDifferentBytesCannotReplaceTheWinningVersion() throws Exception {
    var firstRequest = ready();
    var changed = input(fixture.raw + " ", null);
    var secondRequest = input(changed.document(), publisher.preview(access, changed).previewHash());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var requests = List.of(firstRequest, secondRequest);
      var futures = new ArrayList<Future<String>>();
      for (var request : requests) futures.add(pool.submit(() -> {
        start.await();
        try { publisher.publish(access, request); return "created"; }
        catch (RuleException conflict) { return conflict.code; }
      }));
      start.countDown();
      var outcomes = List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
      assertEquals(1, outcomes.stream().filter("created"::equals).count());
      assertEquals(1, outcomes.stream().filter("PACK_VERSION_CONFLICT"::equals).count());
    }
    assertEquals(1, count("quiz_question_packs"));
    assertEquals(1, count("quiz_question_audit"));
    assertEquals("publication-v1", fixture.bank.select("en", ApprovedQuestionBankTest.NOW).version());
  }

  @Test
  void concurrentIdenticalPublicationCommitsExactlyOnce() throws Exception {
    var request = ready();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<QuestionPackPublisher.Published> call =
          () -> {
            start.await();
            return publisher.publish(access, request);
          };
      var first = pool.submit(call);
      var second = pool.submit(call);
      start.countDown();
      var a = first.get(10, TimeUnit.SECONDS);
      var b = second.get(10, TimeUnit.SECONDS);
      assertNotEquals(a.created(), b.created());
    }
    assertEquals(1, count("quiz_question_packs"));
    assertEquals(1, count("quiz_question_audit"));
  }
}
