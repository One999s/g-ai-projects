package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.content.*;
import com.gaiprojects.quiz.core.RuleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz/content/packs")
public final class ContentController {
  private final ObjectProvider<QuestionPackPublisher> publishers;

  public ContentController(ObjectProvider<QuestionPackPublisher> publishers) {
    this.publishers = publishers;
  }

  private ContentAccess access(HttpServletRequest req) {
    if (!(req.getAttribute(IdentityAdmission.CONTENT_ACCESS) instanceof ContentAccess a))
      throw new RuleException("CONTENT_PUBLICATION_FORBIDDEN", 403);
    a.assertCurrent();
    return a;
  }

  private QuestionPackPublisher publisher() {
    var p = publishers.getIfAvailable();
    if (p == null) throw new RuleException("CONTENT_PUBLICATION_NOT_CONFIGURED", 503);
    return p;
  }

  @PostMapping("/preview")
  public ApiEnvelope preview(
      @RequestBody QuestionPackPublisher.Input input, HttpServletRequest req) {
    return ApiEnvelope.ok(
        (String) req.getAttribute(IdentityAdmission.REQUEST_ID),
        publisher().preview(access(req), input));
  }

  @PostMapping("/publish")
  public ApiEnvelope publish(
      @RequestBody QuestionPackPublisher.Input input, HttpServletRequest req) {
    return ApiEnvelope.ok(
        (String) req.getAttribute(IdentityAdmission.REQUEST_ID),
        publisher().publish(access(req), input));
  }
}
