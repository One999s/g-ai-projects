package com.gaiprojects.quiz.core;

public final class RuleException extends RuntimeException {
  public final String code;
  public final int status;

  public RuleException(String code, int status) {
    super(code);
    this.code = code;
    this.status = status;
  }
}
