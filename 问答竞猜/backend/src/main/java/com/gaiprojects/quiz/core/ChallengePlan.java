package com.gaiprojects.quiz.core;

import java.util.List;

/** Public editorial plan metadata, never question IDs, text or answer keys. */
public record ChallengePlan(String id, String title, List<Slot> slots) {
  public record Slot(String category, int difficulty) {
    public Slot {
      if (category == null
          || !category.matches("[a-z][a-z0-9-]{0,31}")
          || difficulty < 1
          || difficulty > 3) throw new IllegalArgumentException("Invalid slot");
    }
  }

  public ChallengePlan {
    if (id == null
        || !id.matches("[a-z][a-z0-9-]{0,39}")
        || id.equals("free")
        || title == null
        || title.isBlank()
        || title.length() > 100
        || title.codePoints().anyMatch(Character::isISOControl)
        || slots == null
        || slots.size() != 5) throw new IllegalArgumentException("Invalid plan");
    slots = List.copyOf(slots);
    for (int i = 1; i < slots.size(); i++)
      if (slots.get(i).difficulty() < slots.get(i - 1).difficulty())
        throw new IllegalArgumentException("Difficulty must not decrease");
  }
}
