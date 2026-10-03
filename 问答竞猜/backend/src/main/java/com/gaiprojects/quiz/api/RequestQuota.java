package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.core.Player;

/** A distributed implementation is required for real traffic. No permissive production fallback. */
public interface RequestQuota {
  void check(Player player, String path);
}
