package com.gaiprojects.quiz.identity;

import com.gaiprojects.quiz.core.Player;
import jakarta.servlet.http.HttpServletRequest;

public interface ExistingIdentityAdapter {
  /**
   * Perform fresh, bounded verification on EVERY invocation, without renewing or changing identity.
   * Do not consume the body. Verify original login/expiry/revocation/status/site and cookie CSRF if
   * applicable. Never trust client IDs.
   */
  Player resolve(HttpServletRequest request);
}
