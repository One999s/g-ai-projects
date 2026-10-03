package com.gaiprojects.quiz.speech;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiprojects.quiz.core.RuleException;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class LoopbackTranscriberTest {
  HttpServer server;
  ExecutorService threads;
  LoopbackTranscriber client;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2);
    threads = Executors.newFixedThreadPool(2);
    server.setExecutor(threads);
    server.start();
    client =
        new LoopbackTranscriber(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/quiz/asr");
  }

  @AfterEach
  void stop() {
    client.close();
    server.stop(0);
    threads.shutdownNow();
  }

  void reply(int status, String type, byte[] data) {
    server.createContext(
        "/internal/quiz/asr",
        e -> {
          e.getRequestBody().readAllBytes();
          e.getResponseHeaders().set("Content-Type", type);
          if (status == 302)
            e.getResponseHeaders()
                .set(
                    "Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/must-not-follow");
          e.sendResponseHeaders(status, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
  }

  @Test
  void actualHttpCarriesOnlyCanonicalAudioAndLocale() throws Exception {
    var body = new AtomicReference<byte[]>();
    var headers = new AtomicReference<Headers>();
    server.createContext(
        "/internal/quiz/asr",
        e -> {
          body.set(e.getRequestBody().readAllBytes());
          headers.set(e.getRequestHeaders());
          byte[] data = "{\"text\":\"Option B\"}".getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    byte[] wave = SpeechRulesTest.wave(16000);
    assertEquals("Option B", client.transcribe(wave, "en", 1000));
    assertArrayEquals(wave, body.get());
    assertEquals("en", headers.get().getFirst("X-Quiz-Language"));
    assertNull(headers.get().getFirst("Cookie"));
    assertNull(headers.get().getFirst("Authorization"));
    assertNull(headers.get().getFirst("X-User-Id"));
  }

  @Test
  void redirectsAreNotFollowed() {
    var followed = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/must-not-follow",
        e -> {
          followed.incrementAndGet();
          e.sendResponseHeaders(500, -1);
          e.close();
        });
    reply(302, "application/json", "{\"text\":\"B\"}".getBytes());
    assertEquals(
        503,
        assertThrows(
                RuleException.class,
                () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000))
            .status);
    assertEquals(0, followed.get());
  }

  @Test
  void giantStreamingResponseIsBounded() {
    reply(200, "application/json", new byte[100000]);
    assertEquals(
        503,
        assertThrows(
                RuleException.class,
                () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000))
            .status);
  }

  @Test
  void stalledResponseBodyHasTotalDeadline() {
    server.createContext(
        "/internal/quiz/asr",
        e -> {
          e.getRequestBody().readAllBytes();
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, 0);
          e.getResponseBody().write('{');
          e.getResponseBody().flush();
          try {
            Thread.sleep(500);
          } catch (InterruptedException ignored) {
          } finally {
            e.close();
          }
        });
    long start = System.nanoTime();
    assertThrows(
        RuleException.class, () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 100));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
  }

  @Test
  void wrongMediaAndDuplicateJsonAreRejected() {
    reply(200, "text/html", "{\"text\":\"B\"}".getBytes());
    assertThrows(
        RuleException.class, () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000));
    server.removeContext("/internal/quiz/asr");
    reply(200, "application/json", "{\"text\":\"A\",\"text\":\"B\"}".getBytes());
    assertThrows(
        RuleException.class, () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000));
  }

  @Test
  void extraFieldsOrOversizeTranscriptAreRejected() {
    reply(200, "application/json", "{\"text\":\"B\",\"score\":750}".getBytes());
    assertThrows(
        RuleException.class, () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000));
    server.removeContext("/internal/quiz/asr");
    reply(200, "application/json", ("{\"text\":\"" + "a".repeat(161) + "\"}").getBytes());
    assertThrows(
        RuleException.class, () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 1000));
  }

  @Test
  void missingProviderFailsClosed() {
    server.stop(0);
    assertEquals(
        "ASR_UNAVAILABLE",
        assertThrows(
                RuleException.class,
                () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 100))
            .code);
  }

  @Test
  void thirdConcurrentRecognitionIsRejectedWithoutGrowingQueue() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    server.createContext(
        "/internal/quiz/asr",
        e -> {
          e.getRequestBody().readAllBytes();
          entered.countDown();
          try {
            release.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
          byte[] data = "{\"text\":\"B\"}".getBytes();
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    try (var callers = Executors.newFixedThreadPool(2)) {
      var a = callers.submit(() -> client.transcribe(SpeechRulesTest.wave(16000), "en", 2000));
      var b = callers.submit(() -> client.transcribe(SpeechRulesTest.wave(16000), "en", 2000));
      try {
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(
            "ASR_BUSY",
            assertThrows(
                    RuleException.class,
                    () -> client.transcribe(SpeechRulesTest.wave(16000), "en", 100))
                .code);
      } finally {
        release.countDown();
      }
      assertEquals("B", a.get(2, TimeUnit.SECONDS));
      assertEquals("B", b.get(2, TimeUnit.SECONDS));
    }
  }
}
