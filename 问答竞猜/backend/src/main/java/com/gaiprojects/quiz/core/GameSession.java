package com.gaiprojects.quiz.core;

import java.util.*;

/** Aggregate is mutated only inside an authoritative repository transaction. Not an API DTO. */
public final class GameSession {
  public String id;
  public Player player;
  public String locale;
  public String bankVersion;
  public List<Question> questions;
  public List<String> roundIds;
  public int index, score, streak, bestStreak;
  public long revision, createdAt, expiresAt, opensAt, deadline, loadingDeadline;
  public String phase = "LOADING";
  public boolean lifelineUsed;
  public Set<Integer> eliminated = new HashSet<>();
  public List<Result> results = new ArrayList<>();
  public Map<String, AnswerReceipt> answerKeys = new HashMap<>();

  public record Result(
      String roundId,
      Integer selected,
      int correct,
      boolean timedOut,
      int awarded,
      int score,
      long resolvedAt,
      String explanation) {}

  public record AnswerReceipt(String roundId, int choice, Result result) {}
}
