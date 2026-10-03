package com.gaiprojects.quiz.api;

public record ApiEnvelope(int version, String requestId, String mode, Object data, ApiError error) {
  public record ApiError(String code) {}

  public static ApiEnvelope ok(String id, Object data) {
    return new ApiEnvelope(1, id, "server", data, null);
  }

  public static ApiEnvelope error(String id, String code) {
    return new ApiEnvelope(1, id, "server", null, new ApiError(code));
  }
}
