package com.gaiprojects.quiz.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gaiprojects.quiz.QuizApplication;
import com.gaiprojects.quiz.service.GameService;
import com.gaiprojects.quiz.speech.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
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

  @Test
  void actualWaveToPrivateWhisperStillRequiresExplicitAnswerConfirmation() throws Exception {
    var s =
        call(
            "/api/quiz/sessions",
            HttpMethod.POST,
            "{\"locale\":\"en\",\"idempotencyKey\":\"real-private-asr\"}",
            "application/json");
    String id = s.get("sessionId").asText(),
        round = s.get("roundId").asText(),
        base = "/api/quiz/sessions/" + id + "/rounds/" + round;
    var ready = call(base + "/ready", HttpMethod.POST, null, null);
    clock.value.set(ready.get("opensAt").asLong());
    byte[] audio = Files.readAllBytes(Path.of(System.getenv("QUIZ_REAL_ASR_WAV")));
    CanonicalWave.verify(audio);
    var candidate = call(base + "/voice-candidate", HttpMethod.POST, audio, "audio/wav");
    assertEquals(2, candidate.get("choice").asInt());
    assertTrue(candidate.get("requiresConfirmation").asBoolean());
    assertFalse(candidate.get("transcript").asText().isBlank());
    var before = call("/api/quiz/sessions/" + id + "/current", HttpMethod.GET, null, null);
    assertEquals(0, before.get("score").asInt());
    assertTrue(before.get("reveal").isNull());
    var after =
        call(
            base + "/answer",
            HttpMethod.POST,
            "{\"choice\":2,\"idempotencyKey\":\"confirmed-real-speech\"}",
            "application/json");
    assertEquals(100, after.get("session").get("score").asInt());
  }
}
