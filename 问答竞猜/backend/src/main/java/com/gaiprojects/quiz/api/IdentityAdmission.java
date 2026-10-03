package com.gaiprojects.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.*;
import com.gaiprojects.quiz.identity.*;
import com.gaiprojects.quiz.service.GameService;
import com.gaiprojects.quiz.speech.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication and distributed admission precede finite buffering. No guessed identity transport.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class IdentityAdmission extends OncePerRequestFilter {
  public static final String ACCESS = "quiz.verifiedAccess", REQUEST_ID = "quiz.requestId";
  private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private final ObjectProvider<ExistingIdentityAdapter> adapters;
  private final ObjectProvider<RequestQuota> quotas;
  private final ObjectProvider<GameService> services;
  private final ObjectMapper json;
  private final ObjectProvider<VoiceService> voices;

  public IdentityAdmission(
      ObjectProvider<ExistingIdentityAdapter> a,
      ObjectProvider<RequestQuota> q,
      ObjectProvider<GameService> s,
      ObjectProvider<VoiceService> v,
      ObjectMapper json) {
    adapters = a;
    quotas = q;
    services = s;
    voices = v;
    this.json = json;
  }

  private boolean known(String method, String path) {
    if (method.equals("GET"))
      return path.equals("/api/quiz/me/capabilities")
          || path.equals("/api/quiz/me/progress")
          || path.equals("/api/quiz/me/sessions")
          || path.matches("/api/quiz/sessions/" + ID + "/current");
    if (!method.equals("POST")) return false;
    return path.equals("/api/quiz/sessions")
        || path.matches("/api/quiz/sessions/" + ID + "/abandon")
        || path.matches(
            "/api/quiz/sessions/"
                + ID
                + "/rounds/"
                + ID
                + "/(ready|answer|next|fifty-fifty|voice-candidate)");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    req.setAttribute(REQUEST_ID, requestId);
    res.setHeader("X-Request-ID", requestId);
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Cache-Control", "no-store");
    String path = req.getRequestURI(), method = req.getMethod();
    if (path.equals("/api/quiz/status") && method.equals("GET")) {
      chain.doFilter(req, res);
      return;
    }
    if (path.indexOf('%') >= 0
        || path.indexOf(';') >= 0
        || path.indexOf('\\') >= 0
        || path.contains("//")
        || path.codePoints().anyMatch(c -> c < 32 || c > 126)
        || !known(method, path)) {
      deny(req, res, 404, "ROUTE_NOT_FOUND");
      return;
    }
    var adapter = adapters.getIfAvailable();
    if (adapter == null) {
      deny(req, res, 503, "IDENTITY_ADAPTER_NOT_CONFIGURED");
      return;
    }
    Player first;
    try {
      first = resolve(adapter, req);
    } catch (RuleException unauthorized) {
      deny(req, res, 401, unauthorized.code);
      return;
    }
    var quota = quotas.getIfAvailable();
    if (quota == null) {
      deny(req, res, 503, "DISTRIBUTED_ADMISSION_NOT_CONFIGURED");
      return;
    }
    try {
      quota.check(first, path);
    } catch (RuleException rejected) {
      deny(req, res, rejected.status, rejected.code);
      return;
    } catch (RuntimeException unavailable) {
      deny(req, res, 503, "RATE_LIMIT_UNAVAILABLE");
      return;
    }
    if (services.getIfAvailable() == null) {
      deny(req, res, 503, "DURABLE_GAME_RUNTIME_NOT_CONFIGURED");
      return;
    }
    if (!sameOrigin(req)) {
      deny(req, res, 403, "ORIGIN_NOT_ALLOWED");
      return;
    }
    boolean hasWave = path.endsWith("/voice-candidate");
    if (hasWave) {
      var voice = voices.getIfAvailable();
      if (voice == null) {
        deny(req, res, 503, "PRIVATE_ASR_NOT_CONFIGURED");
        return;
      }
      var earlyAccess =
          new VerifiedAccess(
              first,
              () -> {
                if (!first.equals(resolve(adapter, req)))
                  throw new RuleException("AUTHENTICATION_REQUIRED", 401);
              });
      String[] parts = path.split("/");
      try {
        voice.window(earlyAccess, parts[4], parts[6]);
      } catch (RuleException rejected) {
        deny(req, res, rejected.status, rejected.code);
        return;
      }
    }
    boolean hasJson =
        method.equals("POST") && (path.equals("/api/quiz/sessions") || path.endsWith("/answer"));
    byte[] bytes;
    try {
      bytes = read(req, hasJson, hasWave);
    } catch (RuleException invalid) {
      deny(req, res, invalid.status, invalid.code);
      return;
    } catch (IOException unavailable) {
      deny(req, res, 408, "REQUEST_BODY_UNAVAILABLE");
      return;
    }
    var buffered = new BufferedRequest(req, bytes);
    var access =
        new VerifiedAccess(
            first,
            () -> {
              if (!first.equals(resolve(adapter, buffered)))
                throw new RuleException("AUTHENTICATION_REQUIRED", 401);
            });
    try {
      access.assertCurrent();
    } catch (RuleException expired) {
      deny(req, res, 401, "AUTHENTICATION_REQUIRED");
      return;
    }
    buffered.setAttribute(ACCESS, access);
    chain.doFilter(buffered, res);
  }

  private static Player resolve(ExistingIdentityAdapter adapter, HttpServletRequest req) {
    try {
      var p = adapter.resolve(req);
      if (p != null) return p;
    } catch (Exception ignored) {
    }
    throw new RuleException("AUTHENTICATION_REQUIRED", 401);
  }

  private static boolean sameOrigin(HttpServletRequest req) {
    String value = req.getHeader("Origin");
    if (value == null) return true;
    try {
      var origin = URI.create(value);
      if (origin.getUserInfo() != null
          || origin.getHost() == null
          || origin.getRawQuery() != null
          || origin.getFragment() != null
          || !origin.getRawPath().isEmpty()) return false;
      int port =
          origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
      return origin.getScheme().equals(req.getScheme())
          && origin.getHost().equalsIgnoreCase(req.getServerName())
          && port == req.getServerPort();
    } catch (Exception invalid) {
      return false;
    }
  }

  private static byte[] read(HttpServletRequest req, boolean json, boolean wave)
      throws IOException {
    long n = req.getContentLengthLong();
    if (req.getHeader("Transfer-Encoding") != null)
      throw new RuleException("TRANSFER_ENCODING_NOT_ALLOWED", 400);
    if (req.getHeader("Content-Encoding") != null)
      throw new RuleException("CONTENT_ENCODING_NOT_ALLOWED", 415);
    if (!json && !wave) {
      if (n > 0) throw new RuleException("UNEXPECTED_BODY", 400);
      return new byte[0];
    }
    String type = req.getContentType();
    if (wave && (type == null || !type.equalsIgnoreCase("audio/wav")))
      throw new RuleException("CANONICAL_WAV_REQUIRED", 415);
    if (json
        && (type == null
            || !type.toLowerCase(java.util.Locale.ROOT)
                .matches("application/json(\\s*;\\s*charset=utf-8)?")))
      throw new RuleException("JSON_REQUIRED", 415);
    if (n < 0) throw new RuleException("CONTENT_LENGTH_REQUIRED", 411);
    int max = wave ? CanonicalWave.MAX_BYTES : 16384;
    if (n > max) throw new RuleException("REQUEST_TOO_LARGE", 413);
    if (wave && n < 3244) throw new RuleException("CANONICAL_WAV_REQUIRED", 415);
    if (n < 2) throw new RuleException("INVALID_JSON", 400);
    long deadline = System.nanoTime() + 10_000_000_000L;
    var out = new ByteArrayOutputStream((int) n);
    byte[] buffer = new byte[1024];
    var input = req.getInputStream();
    int read;
    while ((read = input.read(buffer)) != -1) {
      if (System.nanoTime() > deadline) throw new RuleException("BODY_READ_BUDGET_EXCEEDED", 408);
      if (out.size() + read > max) throw new RuleException("REQUEST_TOO_LARGE", 413);
      out.write(buffer, 0, read);
    }
    if (out.size() != n) throw new RuleException("CONTENT_LENGTH_MISMATCH", 400);
    byte[] result = out.toByteArray();
    if (wave) CanonicalWave.verify(result);
    return result;
  }

  private void deny(HttpServletRequest req, HttpServletResponse res, int status, String code)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    json.writeValue(
        res.getOutputStream(), ApiEnvelope.error((String) req.getAttribute(REQUEST_ID), code));
  }
}
