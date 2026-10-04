package com.gaiprojects.quiz.core;

import java.util.List;

public record Campaign(String id, String version, String title, List<Level> levels) {
  public record Level(String id, String title, String planId, int requiredCorrect) {
    public Level {
      token(id);
      label(title);
      token(planId);
      if (requiredCorrect < 1 || requiredCorrect > 5)
        throw new IllegalArgumentException("Invalid pass threshold");
    }
  }

  public Campaign {
    token(id);
    token(version);
    label(title);
    if (levels == null || levels.size() != 3)
      throw new IllegalArgumentException("Three chapters required");
    levels = List.copyOf(levels);
    if (levels.stream().map(Level::id).distinct().count() != 3)
      throw new IllegalArgumentException("Duplicate chapter");
  }

  public static void token(String s) {
    if (s == null || !s.matches("[a-z][a-z0-9-]{0,39}") || s.equals("free"))
      throw new IllegalArgumentException("Invalid campaign token");
  }

  public static void label(String s) {
    if (s == null
        || s.isBlank()
        || s.length() > 100
        || s.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid campaign label");
  }
}
