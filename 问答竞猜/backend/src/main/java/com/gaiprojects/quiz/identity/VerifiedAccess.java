package com.gaiprojects.quiz.identity;

import com.gaiprojects.quiz.core.Player;
import java.util.Objects;

/**
 * Request-scoped proof from the trusted filter; never accepted from JSON or client identity
 * headers.
 */
public record VerifiedAccess(Player player, Runnable revalidate) {
  public VerifiedAccess {
    Objects.requireNonNull(player);
    Objects.requireNonNull(revalidate);
  }

  public void assertCurrent() {
    revalidate.run();
  }
}
