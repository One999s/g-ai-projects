package com.gaiprojects.quiz.identity;
import com.gaiprojects.quiz.core.Player;
import jakarta.servlet.http.HttpServletRequest;
public interface ExistingIdentityAdapter {
 /** Verify original login/expiry/revocation/status/site and cookie CSRF if applicable. Never trust client IDs. */
 Player resolve(HttpServletRequest request);
}
