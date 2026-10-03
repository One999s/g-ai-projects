package com.gaiprojects.quiz.speech;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gaiprojects.quiz.core.RuleException;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Exact literal-loopback endpoint; no DNS, proxy, redirects, unbounded response or credential
 * forwarding.
 */
public final class LoopbackTranscriber implements PrivateTranscriber, AutoCloseable {
  private final URI endpoint;
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          r -> {
            var t = new Thread(r, "quiz-private-asr");
            t.setDaemon(true);
            return t;
          });
  private final HttpClient client;
  private final Semaphore capacity = new Semaphore(2);
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  record Reply(String text) {}

  public LoopbackTranscriber(String url) {
    endpoint = validateEndpoint(url);
    client =
        HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofMillis(250))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(
                new ProxySelector() {
                  public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                  }

                  public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
                })
            .build();
  }

  public static URI validateEndpoint(String value) {
    try {
      var uri = URI.create(value);
      if (!"http".equals(uri.getScheme())
          || !"127.0.0.1".equals(uri.getHost())
          || uri.getPort() < 1
          || uri.getPort() > 65535
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getFragment() != null
          || !"/internal/quiz/asr".equals(uri.getRawPath())) throw new IllegalArgumentException();
      return uri;
    } catch (Exception bad) {
      throw new IllegalStateException("EXPLICIT_LOOPBACK_ASR_ENDPOINT_REQUIRED");
    }
  }

  @Override
  public String transcribe(byte[] wave, String locale, long timeoutMillis) {
    CanonicalWave.verify(wave);
    if (!Set.of("en", "zh-CN").contains(locale)) throw new RuleException("UNSUPPORTED_LOCALE", 400);
    if (timeoutMillis < 1) throw new RuleException("VOICE_WINDOW_CLOSED", 409);
    if (!capacity.tryAcquire()) throw new RuleException("ASR_BUSY", 503);
    CompletableFuture<HttpResponse<byte[]>> response = null;
    try {
      long timeout = Math.min(timeoutMillis, 4000);
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofMillis(timeout))
              .header("Content-Type", "audio/wav")
              .header("X-Quiz-Language", locale)
              .POST(HttpRequest.BodyPublishers.ofByteArray(wave))
              .build();
      response = client.sendAsync(request, info -> new LimitedBody());
      var received = response.get(timeout, TimeUnit.MILLISECONDS);
      if (received.headers().firstValue("Content-Encoding").isPresent()
          || received.statusCode() != 200
          || !received
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .split(";")[0]
              .trim()
              .equalsIgnoreCase("application/json")) throw new IOException();
      String raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(received.body()))
              .toString();
      var reply = json.readValue(raw, Reply.class);
      if (reply.text() == null
          || reply.text().length() > 160
          || reply.text().codePoints().anyMatch(Character::isISOControl)) throw new IOException();
      return reply.text().strip();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new RuleException("ASR_UNAVAILABLE", 503);
    } catch (Exception unavailable) {
      throw new RuleException("ASR_UNAVAILABLE", 503);
    } finally {
      if (response != null && !response.isDone()) response.cancel(true);
      capacity.release();
    }
  }

  @Override
  public void close() {
    client.shutdownNow();
    executor.shutdownNow();
  }
}
