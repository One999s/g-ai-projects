package com.gaiprojects.quiz.content;

import com.gaiprojects.quiz.core.Player;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Original-system integration must explicitly authorize GLOBAL quiz text publication. Player/site
 * access alone is insufficient. No production implementation is supplied.
 */
public interface ContentMaintenanceAdapter {
  String authorizeGlobalTextPublication(HttpServletRequest request, Player player);
}
