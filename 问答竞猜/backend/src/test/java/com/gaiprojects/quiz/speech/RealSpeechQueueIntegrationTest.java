package com.gaiprojects.quiz.speech;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.RuleException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Bounded local measurement, never a commercial capacity or production identity acceptance. */
@EnabledIfEnvironmentVariable(named = "QUIZ_REAL_ASR_QUEUE", matches = "true")
class RealSpeechQueueIntegrationTest {
  static final String ENDPOINT = "http://127.0.0.1:29609/internal/quiz/asr";
  static final List<String> FIXTURES = List.of("third-option", "answer-three");

  record Sample(
      String fixture, String status, String transcript, Integer choice, long elapsedMillis) {}

  Sample recognize(LoopbackTranscriber client, int index) throws Exception {
    String fixture = FIXTURES.get(index % FIXTURES.size());
    byte[] wave =
        Files.readAllBytes(
            Path.of("../speech-worker/fixtures/synthetic-zh-" + fixture + "-v1/candidate.wav"));
    long start = System.nanoTime();
    try {
      String text = client.transcribe(wave, "zh-CN", 4000);
      return new Sample(
          fixture,
          "OK",
          text,
          ChoiceMatcher.suggest(text, List.of("A", "B", "C", "D"), Set.of()),
          (System.nanoTime() - start) / 1_000_000);
    } catch (RuleException error) {
      return new Sample(fixture, error.code, null, null, (System.nanoTime() - start) / 1_000_000);
    }
  }

  List<Sample> parallel(int count, List<LoopbackTranscriber> clients) throws Exception {
    var pool = Executors.newFixedThreadPool(count);
    var ready = new CountDownLatch(count);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Sample>>();
      for (int i = 0; i < count; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(2, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start barrier expired");
                  return recognize(clients.get(index % clients.size()), index);
                }));
      }
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      start.countDown();
      var results = new ArrayList<Sample>();
      for (var future : futures) results.add(future.get(6, TimeUnit.SECONDS));
      return results;
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void recordsSerialAndBoundedConcurrentOutcomesWithoutExtendingFourSecondDeadline()
      throws Exception {
    var results = new LinkedHashMap<String, List<Sample>>();
    try (var first = new LoopbackTranscriber(ENDPOINT);
        var second = new LoopbackTranscriber(ENDPOINT)) {
      results.put("serial-two", List.of(recognize(first, 0), recognize(first, 1)));
      results.put("simultaneous-two-one-java-client", parallel(2, List.of(first)));
      results.put("simultaneous-four-one-java-client", parallel(4, List.of(first)));
      // Two independent Java clients model two application instances sharing one ASR queue.
      results.put("simultaneous-four-two-java-clients", parallel(4, List.of(first, second)));
    }
    for (var group : results.values())
      for (var sample : group) {
        assertTrue(Set.of("OK", "ASR_BUSY", "ASR_UNAVAILABLE").contains(sample.status()));
        assertTrue(
            sample.elapsedMillis() < 5000,
            "4s transport deadline plus scheduler overhead exceeded");
      }
    assertEquals(
        2,
        results.get("simultaneous-four-one-java-client").stream()
            .filter(x -> x.status().equals("ASR_BUSY"))
            .count());
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put(
        "modelProfile", System.getenv().getOrDefault("QUIZ_REAL_ASR_MODEL_PROFILE", "tiny"));
    evidence.put("transportDeadlineMillis", 4000);
    evidence.put("singleAsrProcess", true);
    evidence.put("javaCapacityPerClient", 2);
    evidence.put("workerListenBacklog", 4);
    evidence.put("queueDelayIncludedInElapsed", true);
    evidence.put("commercialConcurrencyAcceptance", false);
    evidence.put("results", results);
    String output = Objects.requireNonNull(System.getenv("QUIZ_REAL_ASR_QUEUE_RECEIPT"));
    Files.writeString(
        Path.of(output),
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n",
        StandardOpenOption.CREATE_NEW);
  }
}
