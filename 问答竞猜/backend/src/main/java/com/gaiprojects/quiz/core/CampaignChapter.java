package com.gaiprojects.quiz.core;

/** Immutable chapter contract pinned when the server authorizes creation. */
public record CampaignChapter(
    String campaignId,
    String campaignVersion,
    String definitionHash,
    String title,
    String levelId,
    String levelTitle,
    int index,
    int total,
    int requiredCorrect,
    java.util.List<String> priorLevelIds) {
  public CampaignChapter {
    Campaign.token(campaignId);
    Campaign.token(campaignVersion);
    Campaign.token(levelId);
    Campaign.label(title);
    Campaign.label(levelTitle);
    if (definitionHash == null
        || !definitionHash.matches("[a-f0-9]{64}")
        || total != 3
        || index < 1
        || index > total
        || requiredCorrect < 1
        || requiredCorrect > 5) throw new IllegalArgumentException("Invalid chapter contract");
    priorLevelIds = java.util.List.copyOf(priorLevelIds);
    if (priorLevelIds.size() != index - 1
        || priorLevelIds.stream().distinct().count() != priorLevelIds.size())
      throw new IllegalArgumentException("Invalid prior chapters");
    priorLevelIds.forEach(Campaign::token);
  }
}
