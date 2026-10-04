package com.gaiprojects.quiz.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gaiprojects.quiz.QuizApplication;
import com.gaiprojects.quiz.narration.NarrationBank;
import com.gaiprojects.quiz.service.GameService;
import com.gaiprojects.quiz.speech.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Real private Whisper behind actual game HTTP; identity and question bank remain test-only. */
@SpringBootTest(
    classes = {
      QuizApplication.class,
      GameHttpFlowTest.Fixtures.class,
      RealSpeechHttpIntegrationTest.Processor.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUIZ_REAL_ASR_INTEGRATION", matches = "true")
class RealSpeechHttpIntegrationTest {
  @TestConfiguration
  @Profile("test")
  static class Processor {
    @Bean(destroyMethod = "close")
    LoopbackTranscriber actualWhisper() {
      return new LoopbackTranscriber("http://127.0.0.1:29609/internal/quiz/asr");
    }

    @Bean
    @Primary
    VoiceService actualVoice(GameService game, LoopbackTranscriber actualWhisper) {
      return new VoiceService(game, actualWhisper);
    }
  }

  @Autowired DataSource dataSource;

  @BeforeEach
  void installChineseSyntheticTestPack() throws Exception {
    var db = new JdbcTemplate(dataSource);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM quiz_question_packs WHERE locale='zh-CN'", Integer.class)
        > 0) return;
    var document =
        json.readTree(
            db.queryForObject(
                "SELECT questions_json FROM quiz_question_packs WHERE locale='en'", String.class));
    for (var row : document.get("questions")) {
      var question = (com.fasterxml.jackson.databind.node.ObjectNode) row.get("question");
      question.put("locale", "zh-CN");
      question.put("text", "仅用于自动化测试的合成题目");
    }
    String raw = json.writeValueAsString(document);
    String hash = NarrationBank.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    db.update(
        "INSERT INTO"
            + " quiz_question_packs(pack_version,locale,status,questions_json,content_sha256,reviewer,valid_from_ms,approved_at_ms,created_at_ms)"
            + " SELECT"
            + " pack_version,'zh-CN',status,?,?,reviewer,valid_from_ms,approved_at_ms,created_at_ms"
            + " FROM quiz_question_packs WHERE locale='en'",
        raw,
        hash);
    db.update(
        "INSERT INTO"
            + " quiz_question_audit(audit_id,pack_version,locale,to_status,actor_reference,reason,content_sha256,occurred_at_ms)"
            + " SELECT ?,pack_version,'zh-CN',to_status,actor_reference,reason,?,occurred_at_ms"
            + " FROM quiz_question_audit WHERE locale='en'",
        UUID.randomUUID().toString(),
        hash);
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired GameHttpFlowTest.TestClock clock;
  @LocalServerPort int port;

  JsonNode call(String path, HttpMethod method, Object body, String type) throws Exception {
    var h = new HttpHeaders();
    h.set("X-Test-Actor", "owner");
    if (type != null) h.setContentType(MediaType.parseMediaType(type));
    var r =
        http.exchange(
            "http://127.0.0.1:" + port + path, method, new HttpEntity<>(body, h), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return json.readTree(r.getBody()).get("data");
  }

  static Stream<Arguments> fixedFixtures() {
    String locale = System.getenv().getOrDefault("QUIZ_REAL_ASR_LOCALE", "en");
    if (locale.equals("en"))
      return Stream.of(
          Arguments.of("en", "english-candidate", Path.of(System.getenv("QUIZ_REAL_ASR_WAV")), 2));
    if (!locale.equals("zh-CN"))
      throw new IllegalArgumentException("Explicit supported fixture locale required");
    String profile = System.getenv().getOrDefault("QUIZ_REAL_ASR_MODEL_PROFILE", "tiny");
    assertTrue(Set.of("tiny", "small-2ec96c54").contains(profile));
    // The tiny outputs were inaccurate; small must independently produce an actual candidate.
    // These two actual model outputs were inaccurate. Keep them as safety regressions,
    // never reinterpret their failed recognition as a successful Chinese candidate.
    return Stream.of("third-option", "answer-three")
        .map(
            id ->
                Arguments.of(
                    "zh-CN",
                    id,
                    Path.of("../speech-worker/fixtures/synthetic-zh-" + id + "-v1/candidate.wav"),
                    profile.equals("tiny") ? null : 2));
  }

  @ParameterizedTest(name = "{1}: unchanged game until explicit confirmation")
  @MethodSource("fixedFixtures")
  void actualWaveToPrivateWhisperRequiresExplicitUserChoiceAndConfirmation(
      String locale, String fixtureId, Path wave, Integer expectedCandidate) throws Exception {
    var s =
        call(
            "/api/quiz/sessions",
            HttpMethod.POST,
            json.writeValueAsString(
                Map.of("locale", locale, "idempotencyKey", "real-private-asr-" + fixtureId)),
            "application/json");
    String id = s.get("sessionId").asText(),
        round = s.get("roundId").asText(),
        base = "/api/quiz/sessions/" + id + "/rounds/" + round;
    var ready = call(base + "/ready", HttpMethod.POST, null, null);
    clock.value.set(ready.get("opensAt").asLong());
    byte[] audio = Files.readAllBytes(wave);
    CanonicalWave.verify(audio);
    var original = call("/api/quiz/sessions/" + id + "/current", HttpMethod.GET, null, null);
    long recognitionStarted = System.nanoTime();
    var candidate = call(base + "/voice-candidate", HttpMethod.POST, audio, "audio/wav");
    long recognitionMillis = (System.nanoTime() - recognitionStarted) / 1_000_000;
    assertTrue(candidate.get("requiresConfirmation").asBoolean());
    assertFalse(candidate.get("transcript").asText().isBlank());
    var before = call("/api/quiz/sessions/" + id + "/current", HttpMethod.GET, null, null);
    assertEquals(0, before.get("score").asInt());
    assertTrue(before.get("reveal").isNull());
    assertEquals(original.get("revision"), before.get("revision"));
    assertEquals("ANSWERING", before.get("phase").asText());
    var after =
        call(
            base + "/answer",
            HttpMethod.POST,
            "{\"choice\":2,\"idempotencyKey\":\"confirmed-real-speech\"}",
            "application/json");
    assertEquals(100, after.get("session").get("score").asInt());
    String receipt = System.getenv("QUIZ_REAL_ASR_RECEIPT");
    if (receipt != null) {
      var evidence = json.createObjectNode();
      evidence.put("locale", locale);
      evidence.put(
          "modelProfile", System.getenv().getOrDefault("QUIZ_REAL_ASR_MODEL_PROFILE", "tiny"));
      evidence.put("recognitionHttpMillis", recognitionMillis);
      evidence.put("testClock", true);
      evidence.put("fixtureId", fixtureId);
      evidence.put(
          "automaticCandidateRecognized",
          candidate.get("choice").isInt() && candidate.get("choice").asInt() == 2);
      evidence.put("accurateCandidateExpected", expectedCandidate != null);
      evidence.put("manualChoiceRequired", candidate.get("choice").isNull());
      evidence.put("transcript", candidate.get("transcript").asText());
      evidence.set("candidateChoice", candidate.get("choice"));
      evidence.put("requiresConfirmation", true);
      evidence.put("beforeExplicitConfirmationScore", before.get("score").asInt());
      evidence.put("afterExplicitConfirmationScore", after.get("session").get("score").asInt());
      evidence.put("candidateChangedRevision", false);
      evidence.put("testOnlyIdentityAndQuestionBank", true);
      evidence.put("realUserAudio", false);
      Files.writeString(
          Path.of(receipt + "." + fixtureId + ".json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n",
          StandardOpenOption.CREATE_NEW);
    }
    // Preserve the full safety evidence even when the independent accuracy expectation fails.
    if (expectedCandidate == null)
      assertTrue(candidate.get("choice").isNull(), candidate.toString());
    else
      assertEquals(
          expectedCandidate.intValue(), candidate.get("choice").asInt(), candidate.toString());
  }
}
