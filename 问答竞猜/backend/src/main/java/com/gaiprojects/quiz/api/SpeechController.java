package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.core.RuleException;
import com.gaiprojects.quiz.identity.VerifiedAccess;
import com.gaiprojects.quiz.speech.VoiceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz")
public final class SpeechController {
  private final ObjectProvider<VoiceService> voices;

  public SpeechController(ObjectProvider<VoiceService> voices) {
    this.voices = voices;
  }

  private VerifiedAccess access(HttpServletRequest req) {
    if (!(req.getAttribute(IdentityAdmission.ACCESS) instanceof VerifiedAccess a))
      throw new RuleException("AUTHENTICATION_REQUIRED", 401);
    a.assertCurrent();
    return a;
  }

  @GetMapping("/me/capabilities")
  public ApiEnvelope capabilities(HttpServletRequest req) {
    access(req);
    return ApiEnvelope.ok(
        (String) req.getAttribute(IdentityAdmission.REQUEST_ID),
        Map.of(
            "voiceCandidateConfigured",
            voices.getIfAvailable() != null,
            "maxAudioMillis",
            6000,
            "requiresConfirmation",
            true,
            "processor",
            "same-host-private-asr",
            "audioStored",
            false));
  }

  @PostMapping(value = "/sessions/{id}/rounds/{round}/voice-candidate", consumes = "audio/wav")
  public ApiEnvelope candidate(
      @PathVariable String id,
      @PathVariable String round,
      @RequestBody byte[] wave,
      HttpServletRequest req) {
    var voice = voices.getIfAvailable();
    if (voice == null) throw new RuleException("PRIVATE_ASR_NOT_CONFIGURED", 503);
    return ApiEnvelope.ok(
        (String) req.getAttribute(IdentityAdmission.REQUEST_ID),
        voice.candidate(access(req), id, round, wave));
  }
}
