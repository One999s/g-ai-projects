package com.gaiprojects.quiz.content;

import com.gaiprojects.quiz.core.RuleException;
import java.util.Objects;

/** Request-scoped capability created only after original identity and operator authorization. */
public record ContentAccess(String actorReference, Runnable revalidate) {
  public ContentAccess {
    if (actorReference == null
        || actorReference.isBlank()
        || actorReference.length() > 128
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(actorReference)
        || actorReference.codePoints().anyMatch(Character::isISOControl))
      throw new RuleException("CONTENT_PUBLICATION_FORBIDDEN", 403);
    Objects.requireNonNull(revalidate);
  }

  public void assertCurrent() {
    revalidate.run();
  }
}
