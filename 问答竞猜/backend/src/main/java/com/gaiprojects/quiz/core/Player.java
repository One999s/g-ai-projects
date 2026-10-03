package com.gaiprojects.quiz.core;
/** Only the verified existing identity adapter can establish these scopes. No team scope. */
public record Player(int siteId, int siteUserId) {
  public Player { if (siteId <= 0 || siteUserId <= 0) throw new IllegalArgumentException("Invalid player scope"); }
}
