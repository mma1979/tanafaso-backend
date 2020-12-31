package com.azkar.factories.entities;

import com.azkar.entities.Challenge;
import com.azkar.entities.Challenge.SubChallenges;
import com.google.common.collect.ImmutableList;
import java.time.Instant;

public class ChallengeFactory {

  public static final String CHALLENGE_NAME_BASE = "challenge_name";
  public final static String CHALLENGE_MOTIVATION = "challenge_motivation";
  public final static long EXPIRY_DATE_OFFSET = 60 * 60;
  public final static SubChallenges SUB_CHALLENGE_1 = SubChallenges.builder()
      .zekrId(1)
      .zekr("zekr")
      .leftRepetitions(3)
      .originalRepetitions(3)
      .build();
  public final static SubChallenges SUB_CHALLENGE_2 = SubChallenges.builder()
      .zekrId(2)
      .zekr("zekr2")
      .leftRepetitions(5)
      .originalRepetitions(5)
      .build();
  private static int challengesRequested = 0;

  public static Challenge getNewChallenge(String groupId) {
    return getNewChallenge(/* namePrefix= */ "", groupId);
  }

  public static Challenge getNewChallenge(String namePrefix, String groupId) {
    long expiryDate = Instant.now().getEpochSecond() + EXPIRY_DATE_OFFSET;
    String challengeFullName = namePrefix + CHALLENGE_NAME_BASE + ++challengesRequested;
    return Challenge.builder()
        .id(challengeFullName)
        .name(challengeFullName)
        .motivation(CHALLENGE_MOTIVATION)
        .expiryDate(expiryDate)
        .subChallenges(ImmutableList.of(SUB_CHALLENGE_1, SUB_CHALLENGE_2))
        .groupId(groupId)
        .build();
  }
}
