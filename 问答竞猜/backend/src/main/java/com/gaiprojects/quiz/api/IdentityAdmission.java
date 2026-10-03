package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.identity.ExistingIdentityAdapter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Default-deny before framework JSON parsing; no fabricated auth transport or token algorithm. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class IdentityAdmission extends OncePerRequestFilter {
  private final ObjectProvider<ExistingIdentityAdapter> adapters;

  public IdentityAdmission(ObjectProvider<ExistingIdentityAdapter> adapters) {
    this.adapters = adapters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Cache-Control", "no-store");
    String path = req.getRequestURI();
    if (path.equals("/api/quiz/status") && req.getMethod().equals("GET")) {
      chain.doFilter(req, res);
      return;
    }
    if (!path.startsWith("/api/quiz/")) {
      chain.doFilter(req, res);
      return;
    }
    var adapter = adapters.getIfAvailable();
    if (adapter == null) {
      deny(res, 503, "IDENTITY_ADAPTER_NOT_CONFIGURED");
      return;
    }
    try {
      var player = adapter.resolve(req);
      if (player == null) {
        deny(res, 401, "AUTHENTICATION_REQUIRED");
        return;
      }
      req.setAttribute("quiz.verifiedPlayer", player);
    } catch (Exception unavailable) {
      deny(res, 401, "AUTHENTICATION_REQUIRED");
      return;
    }
    // Business API stays unavailable until durable repository and approved pack are configured.
    deny(res, 503, "DURABLE_GAME_RUNTIME_NOT_CONFIGURED");
  }

  private static void deny(HttpServletResponse res, int status, String code) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter().write("{\"error\":\"" + code + "\"}");
  }
}
