package com.gaiprojects.quiz.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gaiprojects.quiz.QuizApplication;
import com.gaiprojects.quiz.core.*;
import com.gaiprojects.quiz.identity.ExistingIdentityAdapter;
import com.gaiprojects.quiz.narration.NarrationBank;
import com.gaiprojects.quiz.service.GameService;
import com.gaiprojects.quiz.speech.*;
import com.gaiprojects.quiz.store.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = {QuizApplication.class, GameHttpFlowTest.Fixtures.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameHttpFlowTest {
  static final long NOW = System.currentTimeMillis();

  static final class TestClock extends Clock {
    final AtomicLong value = new AtomicLong(NOW);
    volatile boolean live;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis());
    }

    public long millis() {
      return live ? System.currentTimeMillis() : value.get();
    }
  }

  static final class AuthState {
    final AtomicLong until = new AtomicLong(Long.MAX_VALUE);
    volatile boolean expireDuringQuota;
    volatile boolean denyQuota;
  }

  static final class SpeechState {
    String text = "Option C";
    int calls;
    Runnable during = () -> {};
  }

  /**
   * Test sources only: absent from production classes/JAR and inactive without the explicit test
   * profile.
   */
  @TestConfiguration
  @Profile("test")
  static class Fixtures {
    @Bean
    NarrationBank fixtureNarration() throws Exception {
      // Synthetic silent fixture, not evidence that any shipped audio was listened to.
      Path dir = Files.createTempDirectory("quiz-narration-fixture-");
      var b = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN);
      b.putInt(0x46464952)
          .putInt(32036)
          .putInt(0x45564157)
          .putInt(0x20746d66)
          .putInt(16)
          .putShort((short) 1)
          .putShort((short) 1)
          .putInt(16000)
          .putInt(32000)
          .putShort((short) 2)
          .putShort((short) 16)
          .putInt(0x61746164)
          .putInt(32000);
      byte[] wave = b.array();
      String sha = NarrationBank.sha(wave);
      var entries = new ArrayList<NarrationBank.Entry>();
      for (int i = 0; i < 15; i++)
        entries.add(
            new NarrationBank.Entry(
                "HTTP_TEST_ONLY",
                "http-test-" + i,
                "en",
                "SYNTHETIC TEST QUESTION",
                List.of("A", "B", "C", "D"),
                3000,
                sha,
                1000,
                true,
                "TEST_FIXTURE_ONLY",
                NOW - 2000,
                "Synthetic silent fixture, no real listening claim"));
      byte[] manifest =
          new ObjectMapper().writeValueAsBytes(new NarrationBank.Manifest(1, entries));
      try {
        Files.write(dir.resolve(sha + ".wav"), wave);
        Files.write(dir.resolve("manifest.json"), manifest);
        return new NarrationBank(dir, NarrationBank.sha(manifest), NOW);
      } finally {
        Files.deleteIfExists(dir.resolve(sha + ".wav"));
        Files.deleteIfExists(dir.resolve("manifest.json"));
        Files.deleteIfExists(dir);
      }
    }

    @Bean
    SpeechState fixtureSpeech() {
      return new SpeechState();
    }

    @Bean
    VoiceService fixtureVoice(GameService game, SpeechState state) {
      return new VoiceService(
          game,
          (bytes, locale, timeout) -> {
            state.calls++;
            state.during.run();
            return state.text;
          });
    }

    @Bean
    TestClock fixtureClock() {
      return new TestClock();
    }

    @Bean
    AuthState fixtureAuth() {
      return new AuthState();
    }

    @Bean
    DataSource fixtureDataSource() throws Exception {
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:http"
                  + UUID.randomUUID()
                  + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
              "sa",
              "");
      var db = new JdbcTemplate(ds);
      String ddl;
      try (var in =
          getClass().getResourceAsStream("/db/migration/V001__quiz_business_tables.sql")) {
        ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      try (var in =
          getClass().getResourceAsStream("/db/migration/V002__quiz_chapter_progress.sql")) {
        ddl += "\n" + new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      for (String part :
          ddl.replaceAll("(?m)^--.*$", "")
              .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "")
              .split(";")) if (!part.isBlank()) db.execute(part);
      var list = new ArrayList<ApprovedQuestionBank.ReviewedQuestion>();
      for (int i = 0; i < 15; i++)
        list.add(
            new ApprovedQuestionBank.ReviewedQuestion(
                new Question(
                    "http-test-" + i,
                    "en",
                    "SYNTHETIC TEST QUESTION",
                    List.of("A", "B", "C", "D"),
                    2,
                    "SYNTHETIC TEST EXPLANATION",
                    3000),
                List.of("https://science.nasa.gov/"),
                "Synthetic test only; not commercial approval",
                "TEST_ONLY",
                NOW - 3000,
                "space",
                1 + i / 5));
      var plans = new ArrayList<ChallengePlan>();
      plans.add(
          new ChallengePlan(
              "rising",
              "TEST ONLY ROUTE",
              List.of(
                  new ChallengePlan.Slot("space", 1),
                  new ChallengePlan.Slot("space", 1),
                  new ChallengePlan.Slot("space", 2),
                  new ChallengePlan.Slot("space", 2),
                  new ChallengePlan.Slot("space", 3))));
      var levels = new ArrayList<Campaign.Level>();
      for (int n = 1; n <= 3; n++) {
        plans.add(
            new ChallengePlan(
                "plan-" + n,
                "TEST PLAN " + n,
                Collections.nCopies(5, new ChallengePlan.Slot("space", n))));
        levels.add(
            new Campaign.Level("chapter-" + n, "TEST CHAPTER " + n, "plan-" + n, n == 3 ? 4 : 3));
      }
      String raw =
          new ObjectMapper()
              .writeValueAsString(
                  new ApprovedQuestionBank.PackDocument(
                      4, list, plans, new Campaign("journey", "r1", "TEST JOURNEY", levels)));
      String hash =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(raw.getBytes(StandardCharsets.UTF_8)));
      db.update(
          "INSERT INTO"
              + " quiz_question_packs(pack_version,locale,status,questions_json,content_sha256,reviewer,valid_from_ms,approved_at_ms,created_at_ms)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          "HTTP_TEST_ONLY",
          "en",
          "approved",
          raw,
          hash,
          "TEST_ONLY",
          NOW - 4000,
          NOW - 2000,
          NOW - 4000);
      db.update(
          "INSERT INTO"
              + " quiz_question_audit(audit_id,pack_version,locale,to_status,actor_reference,reason,content_sha256,occurred_at_ms)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          "HTTP_TEST_ONLY",
          "en",
          "approved",
          "TEST_ONLY",
          "Synthetic fixture",
          hash,
          NOW - 2000);
      return ds;
    }

    @Bean
    JdbcGameStore fixtureStore(DataSource ds, ObjectMapper json) {
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
      return new JdbcGameStore(new JdbcTemplate(ds), tx, json);
    }

    @Bean
    ApprovedQuestionBank fixtureBank(DataSource ds) {
      return new ApprovedQuestionBank(new JdbcTemplate(ds), new Random(3));
    }

    @Bean
    GameService fixtureGame(JdbcGameStore s, ApprovedQuestionBank b, TestClock c) {
      return new GameService(s, b, new GameRules(new Random(4)), c);
    }

    @Bean
    ExistingIdentityAdapter fixtureIdentity(AuthState a, TestClock c) {
      return req -> {
        if (c.millis() >= a.until.get()) return null;
        return switch (String.valueOf(req.getHeader("X-Test-Actor"))) {
          case "other-site" -> new Player(8, 42);
          case "other-user" -> new Player(7, 43);
          default -> new Player(7, 42);
        };
      };
    }

    @Bean
    RequestQuota fixtureQuota(AuthState a, TestClock c) {
      return (p, path) -> {
        if (a.denyQuota) throw new RuleException("RATE_LIMITED", 429);
        if (a.expireDuringQuota) a.until.set(c.millis());
      };
    }
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired TestClock clock;
  @Autowired SpeechState speech;
  @Autowired AuthState auth;
  @Autowired DataSource ds;
  @LocalServerPort int port;

  @BeforeEach
  void reset() {
    speech.text = "Option C";
    speech.calls = 0;
    speech.during = () -> {};
    clock.live = false;
    clock.value.set(NOW);
    auth.until.set(Long.MAX_VALUE);
    auth.expireDuringQuota = false;
    auth.denyQuota = false;
    var db = new JdbcTemplate(ds);
    for (String t :
        List.of(
            "quiz_chapter_progress",
            "quiz_outbox",
            "quiz_scores",
            "quiz_progress",
            "quiz_sessions")) db.execute("DELETE FROM " + t);
    db.update("UPDATE quiz_question_packs SET status='approved'");
  }

  ResponseEntity<String> request(String path, HttpMethod method, String body, String actor) {
    var headers = new HttpHeaders();
    headers.set("X-Test-Actor", actor);
    if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(
        "http://127.0.0.1:" + port + path, method, new HttpEntity<>(body, headers), String.class);
  }

  JsonNode data(ResponseEntity<String> response) throws Exception {
    assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
    return json.readTree(response.getBody()).get("data");
  }

  JsonNode create(String key) throws Exception {
    return data(
        request(
            "/api/quiz/sessions",
            HttpMethod.POST,
            "{\"locale\":\"en\",\"idempotencyKey\":\"" + key + "\"}",
            "owner"));
  }

  String roundPath(JsonNode s) {
    return "/api/quiz/sessions/"
        + s.get("sessionId").asText()
        + "/rounds/"
        + s.get("roundId").asText();
  }

  @Test
  void sourceJavaScriptClientCompletesActualServerGame() throws Exception {
    clock.live = true;
    var script =
        java.nio.file.Path.of("../frontend/tests/http-server-smoke.mjs")
            .toAbsolutePath()
            .normalize();
    assertTrue(java.nio.file.Files.isRegularFile(script));
    var process =
        new ProcessBuilder("node", script.toString(), "http://127.0.0.1:" + port)
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "HTTP client timeout");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains("\"score\":750"), output);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  @Test
  void replayUsesOriginalSessionEvenAfterPackRetirement() throws Exception {
    String key = "repeat-" + UUID.randomUUID();
    var a = create(key);
    new JdbcTemplate(ds).update("UPDATE quiz_question_packs SET status='retired'");
    var b = create(key);
    assertEquals(a.get("sessionId"), b.get("sessionId"));
    var newGame =
        request(
            "/api/quiz/sessions",
            HttpMethod.POST,
            "{\"locale\":\"en\",\"idempotencyKey\":\"brand-new-key\"}",
            "owner");
    assertEquals(503, newGame.getStatusCode().value());
  }

  @Test
  void crossUserAndSiteReadsAre404() throws Exception {
    var s = create("scope-" + UUID.randomUUID());
    String path = "/api/quiz/sessions/" + s.get("sessionId").asText() + "/current";
    for (String actor : List.of("other-user", "other-site"))
      assertEquals(404, request(path, HttpMethod.GET, null, actor).getStatusCode().value());
  }

  @Test
  void deadlineResponseCommitsTimeoutAndIsRecoverable() throws Exception {
    var s = create("deadline-" + UUID.randomUUID());
    var ready = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    clock.value.set(ready.get("deadline").asLong());
    var late =
        request(
            roundPath(s) + "/answer",
            HttpMethod.POST,
            "{\"choice\":2,\"idempotencyKey\":\"late-answer\"}",
            "owner");
    assertEquals(409, late.getStatusCode().value());
    var current =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/current",
                HttpMethod.GET,
                null,
                "owner"));
    assertTrue(current.get("reveal").get("timedOut").asBoolean());
    assertEquals(0, current.get("score").asInt());
  }

  @Test
  void quotaExpiryUnknownFieldsAndOversizedInputCannotCreateSessions() throws Exception {
    auth.denyQuota = true;
    assertEquals(
        429,
        request("/api/quiz/sessions", HttpMethod.POST, "{bad json", "owner")
            .getStatusCode()
            .value());
    auth.denyQuota = false;
    auth.expireDuringQuota = true;
    assertEquals(
        401,
        request(
                "/api/quiz/sessions",
                HttpMethod.POST,
                "{\"locale\":\"en\",\"idempotencyKey\":\"expired-key\"}",
                "owner")
            .getStatusCode()
            .value());
    auth.expireDuringQuota = false;
    auth.until.set(Long.MAX_VALUE);
    assertEquals(
        400,
        request(
                "/api/quiz/sessions",
                HttpMethod.POST,
                "{\"locale\":\"en\",\"idempotencyKey\":\"extra-key\",\"siteUserId\":42}",
                "owner")
            .getStatusCode()
            .value());
    assertEquals(
        413,
        request("/api/quiz/sessions", HttpMethod.POST, " ".repeat(20000), "owner")
            .getStatusCode()
            .value());
    assertEquals(
        0,
        new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
  }

  @Test
  void originBoundaryRejectsForeignPortUserInfoAndNullOrigins() throws Exception {
    for (String origin :
        List.of(
            "null",
            "https://evil.invalid",
            "http://127.0.0.1:" + (port + 1),
            "http://user@127.0.0.1:" + port)) {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set("Origin", origin);
      var response =
          http.exchange(
              "http://127.0.0.1:" + port + "/api/quiz/sessions",
              HttpMethod.POST,
              new HttpEntity<>(
                  "{\"locale\":\"en\",\"idempotencyKey\":\"origin-test-key\"}", headers),
              String.class);
      assertEquals(403, response.getStatusCode().value(), origin);
    }
    assertEquals(
        0,
        new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM quiz_sessions", Integer.class));
  }

  @Test
  void duplicateKeysAndScalarCoercionAreRejected() throws Exception {
    assertEquals(
        400,
        request(
                "/api/quiz/sessions",
                HttpMethod.POST,
                "{\"locale\":\"zh-CN\",\"locale\":\"en\",\"idempotencyKey\":\"duplicate-json\"}",
                "owner")
            .getStatusCode()
            .value());
    var s = create("types-" + UUID.randomUUID());
    assertEquals(
        400,
        request(
                roundPath(s) + "/answer",
                HttpMethod.POST,
                "{\"choice\":\"2\",\"idempotencyKey\":\"string-choice\"}",
                "owner")
            .getStatusCode()
            .value());
  }

  @Test
  void lifelineAbandonAndInvalidReadyBodyHaveBoundedEffects() throws Exception {
    var s = create("life-" + UUID.randomUUID());
    assertEquals(
        400,
        request(roundPath(s) + "/ready", HttpMethod.POST, "{}", "owner").getStatusCode().value());
    var ready = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    clock.value.set(ready.get("opensAt").asLong());
    var fifty = data(request(roundPath(s) + "/fifty-fifty", HttpMethod.POST, null, "owner"));
    assertEquals(2, fifty.get("eliminated").size());
    assertEquals(
        409,
        request(roundPath(s) + "/fifty-fifty", HttpMethod.POST, null, "owner")
            .getStatusCode()
            .value());
    var abandoned =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/abandon",
                HttpMethod.POST,
                null,
                "owner"));
    assertEquals("ABANDONED", abandoned.get("phase").asText());
  }

  byte[] wave() {
    var b = java.nio.ByteBuffer.allocate(32044).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(32036)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(32000);
    return b.array();
  }

  ResponseEntity<String> voice(JsonNode s, byte[] bytes, String actor) {
    var headers = new HttpHeaders();
    headers.set("X-Test-Actor", actor);
    headers.setContentType(MediaType.parseMediaType("audio/wav"));
    return http.exchange(
        "http://127.0.0.1:" + port + roundPath(s) + "/voice-candidate",
        HttpMethod.POST,
        new HttpEntity<>(bytes, headers),
        String.class);
  }

  JsonNode answering() throws Exception {
    var s = create("voice-" + UUID.randomUUID());
    var r = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    clock.value.set(r.get("opensAt").asLong());
    return s;
  }

  @Test
  void speechCandidateNeedsSeparateAuthoritativeConfirmation() throws Exception {
    var s = answering();
    var candidate = data(voice(s, wave(), "owner"));
    assertEquals(2, candidate.get("choice").asInt());
    assertTrue(candidate.get("requiresConfirmation").asBoolean());
    assertFalse(candidate.has("correct"));
    var current =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/current",
                HttpMethod.GET,
                null,
                "owner"));
    assertEquals(0, current.get("score").asInt());
    assertTrue(current.get("reveal").isNull());
    assertEquals(
        0, new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM quiz_scores", Integer.class));
    var confirmed =
        data(
            request(
                roundPath(s) + "/answer",
                HttpMethod.POST,
                "{\"choice\":2,\"idempotencyKey\":\"voice-confirmed\"}",
                "owner"));
    assertEquals(100, confirmed.get("session").get("score").asInt());
    assertEquals(409, voice(s, wave(), "owner").getStatusCode().value());
    assertEquals(1, speech.calls);
  }

  @Test
  void ownershipAndPhaseAreCheckedBeforeMalformedAudio() throws Exception {
    var s = create("voice-early");
    assertEquals(409, voice(s, new byte[3], "owner").getStatusCode().value());
    assertEquals(404, voice(s, new byte[3], "other-site").getStatusCode().value());
    assertEquals(404, voice(s, new byte[3], "other-user").getStatusCode().value());
    assertEquals(0, speech.calls);
  }

  @Test
  void speechDeadlineDuringRecognitionDropsCandidateAndCommitsCanonicalTimeout() throws Exception {
    var s = answering();
    speech.during = () -> clock.value.addAndGet(20000);
    assertEquals(409, voice(s, wave(), "owner").getStatusCode().value());
    var current =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/current",
                HttpMethod.GET,
                null,
                "owner"));
    assertTrue(current.get("reveal").get("timedOut").asBoolean());
    assertEquals(0, current.get("score").asInt());
  }

  @Test
  void revokedIdentityDuringRecognitionCannotReturnTranscript() throws Exception {
    var s = answering();
    speech.during = () -> auth.until.set(clock.millis());
    var r = voice(s, wave(), "owner");
    assertEquals(401, r.getStatusCode().value());
    assertFalse(r.getBody().contains("transcript"));
    assertEquals(
        0, new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM quiz_scores", Integer.class));
  }

  @Test
  void ambiguousAndEliminatedSpeechNeverChangesScore() throws Exception {
    var s = answering();
    speech.text = "A or B";
    assertTrue(data(voice(s, wave(), "owner")).get("choice").isNull());
    var fifty = data(request(roundPath(s) + "/fifty-fifty", HttpMethod.POST, null, "owner"));
    speech.text =
        "ABCD"
            .substring(
                fifty.get("eliminated").get(0).asInt(), fifty.get("eliminated").get(0).asInt() + 1);
    assertTrue(data(voice(s, wave(), "owner")).get("choice").isNull());
    assertEquals(0, fifty.get("score").asInt());
  }

  @Test
  void invalidOrTooLargeAudioNeverReachesPrivateProcessor() throws Exception {
    var s = answering();
    assertEquals(415, voice(s, new byte[32044], "owner").getStatusCode().value());
    assertEquals(413, voice(s, new byte[192046], "owner").getStatusCode().value());
    assertEquals(0, speech.calls);
    assertTrue(
        data(request("/api/quiz/me/capabilities", HttpMethod.GET, null, "owner"))
            .get("voiceCandidateConfigured")
            .asBoolean());
  }

  @Test
  void narrationHttpIsScopedBoundedAndDoesNotScore() throws Exception {
    var s = create("narration-" + UUID.randomUUID());
    String path = roundPath(s) + "/narration";
    assertEquals(409, request(path, HttpMethod.GET, null, "owner").getStatusCode().value());
    assertEquals(
        409, request(path + "/audio", HttpMethod.GET, null, "owner").getStatusCode().value());
    data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    var m = data(request(path, HttpMethod.GET, null, "owner"));
    assertTrue(m.get("available").asBoolean());
    assertEquals(32044, m.get("byteLength").asInt());
    assertFalse(m.has("answer"));
    assertFalse(m.has("questionId"));
    assertFalse(m.has("bankVersion"));
    var headers = new HttpHeaders();
    headers.set("X-Test-Actor", "owner");
    var wav =
        http.exchange(
            "http://127.0.0.1:" + port + path + "/audio",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            byte[].class);
    assertEquals(200, wav.getStatusCode().value());
    assertEquals(32044, wav.getBody().length);
    assertEquals(m.get("sha256").asText(), NarrationBank.sha(wav.getBody()));
    assertEquals("no-store", wav.getHeaders().getCacheControl());
    assertEquals(404, request(path, HttpMethod.GET, null, "other-site").getStatusCode().value());
    assertEquals(
        404, request(path + "/audio", HttpMethod.GET, null, "other-user").getStatusCode().value());
    assertEquals(
        409,
        request(
                path.replace(s.get("roundId").asText(), UUID.randomUUID().toString()),
                HttpMethod.GET,
                null,
                "owner")
            .getStatusCode()
            .value());
    var ready = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    assertEquals(0, ready.get("score").asInt());
    assertTrue(data(request(path, HttpMethod.GET, null, "owner")).get("available").asBoolean());
    clock.value.set(ready.get("opensAt").asLong());
    assertEquals(
        409, request(path + "/audio", HttpMethod.GET, null, "owner").getStatusCode().value());
  }

  @Test
  void narrationHttpRechecksIdentityAndRejectsExpiredLoading() throws Exception {
    var s = create("narration-closed-" + UUID.randomUUID());
    String path = roundPath(s) + "/narration";
    auth.expireDuringQuota = true;
    assertEquals(401, request(path, HttpMethod.GET, null, "owner").getStatusCode().value());
    auth.expireDuringQuota = false;
    auth.until.set(Long.MAX_VALUE);
    clock.value.set(s.get("loadingDeadline").asLong());
    assertEquals(409, request(path, HttpMethod.GET, null, "owner").getStatusCode().value());
  }

  @Test
  void combinedRecordHttpIsEmptyBeforeFinishAndFailsOnIdentityLoss() throws Exception {
    var value = data(request("/api/quiz/me/record", HttpMethod.GET, null, "owner"));
    assertEquals(20, value.get("limit").asInt());
    assertEquals(0, value.get("progress").get("gamesPlayed").asInt());
    assertEquals(0, value.get("recent").size());
    create("record-incomplete");
    assertEquals(
        0,
        data(request("/api/quiz/me/record", HttpMethod.GET, null, "owner")).get("recent").size());
    auth.expireDuringQuota = true;
    assertEquals(
        401, request("/api/quiz/me/record", HttpMethod.GET, null, "owner").getStatusCode().value());
  }

  @Test
  void unopenedAndAbandonedHttpNeverDisclosePromptOrOptions() throws Exception {
    var s = create("hidden-" + UUID.randomUUID());
    assertTrue(s.get("question").isNull());
    assertEquals(0, s.get("options").size());
    var current =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/current",
                HttpMethod.GET,
                null,
                "owner"));
    assertTrue(current.get("question").isNull());
    var ready = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
    assertEquals("READING", ready.get("phase").asText());
    assertEquals("SYNTHETIC TEST QUESTION", ready.get("question").asText());
    assertEquals(4, ready.get("options").size());
    assertTrue(ready.get("reveal").isNull());
    var abandoned =
        data(
            request(
                "/api/quiz/sessions/" + s.get("sessionId").asText() + "/abandon",
                HttpMethod.POST,
                null,
                "owner"));
    assertTrue(abandoned.get("question").isNull());
    assertEquals(0, abandoned.get("options").size());
    assertTrue(abandoned.get("reveal").isNull());
  }

  @Test
  void challengeCatalogAndCreationBindVersionWithoutDisclosingQuestions() throws Exception {
    var catalog = data(request("/api/quiz/challenges?locale=en", HttpMethod.GET, null, "owner"));
    assertEquals(4, catalog.get("plans").size());
    assertFalse(catalog.toString().contains("http-test-"));
    assertFalse(catalog.toString().contains("SYNTHETIC TEST QUESTION"));
    var body =
        json.writeValueAsString(
            Map.of(
                "locale",
                "en",
                "idempotencyKey",
                "route-test-key",
                "challengeId",
                "rising",
                "challengeVersion",
                catalog.get("version").asText()));
    var s = data(request("/api/quiz/sessions", HttpMethod.POST, body, "owner"));
    assertEquals("rising", s.get("challenge").get("id").asText());
    assertTrue(s.get("question").isNull());
    assertEquals(
        s.get("sessionId"),
        data(request("/api/quiz/sessions", HttpMethod.POST, body, "owner")).get("sessionId"));
    assertEquals(
        409,
        request(
                "/api/quiz/sessions",
                HttpMethod.POST,
                json.writeValueAsString(Map.of("locale", "en", "idempotencyKey", "route-test-key")),
                "owner")
            .getStatusCode()
            .value());
    assertEquals(
        409,
        request(
                "/api/quiz/sessions",
                HttpMethod.POST,
                body.replace("HTTP_TEST_ONLY", "OLD_VERSION"),
                "owner")
            .getStatusCode()
            .value());
    for (int i = 0; i < 5; i++) {
      s = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
      clock.value.set(s.get("opensAt").asLong());
      s =
          data(request(
                  roundPath(s) + "/answer",
                  HttpMethod.POST,
                  json.writeValueAsString(
                      Map.of("choice", 2, "idempotencyKey", "route-answer-" + i)),
                  "owner"))
              .get("session");
      assertEquals("rising", s.get("challenge").get("id").asText());
      s = data(request(roundPath(s) + "/next", HttpMethod.POST, null, "owner"));
    }
    assertEquals("FINISHED", s.get("phase").asText());
    assertEquals(750, s.get("score").asInt());
  }

  @Test
  void challengeCatalogFailsClosedWhenAccessExpiresOrLocaleIsInvalid() throws Exception {
    assertEquals(
        400,
        request("/api/quiz/challenges?locale=xx", HttpMethod.GET, null, "owner")
            .getStatusCode()
            .value());
    auth.expireDuringQuota = true;
    assertEquals(
        401,
        request("/api/quiz/challenges?locale=en", HttpMethod.GET, null, "owner")
            .getStatusCode()
            .value());
  }

  JsonNode playChapterHttp(String level, int correct, String key) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "locale",
                "en",
                "idempotencyKey",
                key,
                "chapterId",
                level,
                "challengeVersion",
                "HTTP_TEST_ONLY"));
    var s = data(request("/api/quiz/sessions", HttpMethod.POST, body, "owner"));
    assertEquals(level, s.get("chapter").get("levelId").asText());
    assertTrue(s.get("question").isNull());
    for (int i = 0; i < 5; i++) {
      s = data(request(roundPath(s) + "/ready", HttpMethod.POST, null, "owner"));
      clock.value.set(s.get("opensAt").asLong());
      s =
          data(request(
                  roundPath(s) + "/answer",
                  HttpMethod.POST,
                  json.writeValueAsString(
                      Map.of(
                          "choice", i < correct ? 2 : 0, "idempotencyKey", key + "-answer-" + i)),
                  "owner"))
              .get("session");
      s = data(request(roundPath(s) + "/next", HttpMethod.POST, null, "owner"));
    }
    return s;
  }

  @Test
  void actualHttpJourneyFailsThenReplaysAndUnlocksThreePersistentChapters() throws Exception {
    String path = "/api/quiz/journey?locale=en";
    var j = data(request(path, HttpMethod.GET, null, "owner"));
    assertTrue(j.get("levels").get(0).get("unlocked").asBoolean());
    assertFalse(j.get("levels").get(1).get("unlocked").asBoolean());
    String locked =
        json.writeValueAsString(
            Map.of(
                "locale",
                "en",
                "idempotencyKey",
                "skip-chapter",
                "chapterId",
                "chapter-2",
                "challengeVersion",
                "HTTP_TEST_ONLY"));
    assertEquals(
        409,
        request("/api/quiz/sessions", HttpMethod.POST, locked, "owner").getStatusCode().value());
    assertFalse(playChapterHttp("chapter-1", 2, "first-failure").get("chapterPassed").asBoolean());
    j = data(request(path, HttpMethod.GET, null, "owner"));
    assertFalse(j.get("levels").get(1).get("unlocked").asBoolean());
    assertTrue(playChapterHttp("chapter-1", 3, "first-replay").get("chapterPassed").asBoolean());
    j = data(request(path, HttpMethod.GET, null, "owner"));
    assertEquals(2, j.get("levels").get(0).get("attempts").asInt());
    assertTrue(j.get("levels").get(1).get("unlocked").asBoolean());
    assertFalse(j.get("levels").get(2).get("unlocked").asBoolean());
    assertTrue(playChapterHttp("chapter-2", 3, "second-clear").get("chapterPassed").asBoolean());
    assertTrue(playChapterHttp("chapter-3", 4, "third-clear").get("chapterPassed").asBoolean());
    j = data(request(path, HttpMethod.GET, null, "owner"));
    for (var l : j.get("levels")) assertTrue(l.get("passed").asBoolean());
    auth.expireDuringQuota = true;
    assertEquals(401, request(path, HttpMethod.GET, null, "owner").getStatusCode().value());
  }
}
