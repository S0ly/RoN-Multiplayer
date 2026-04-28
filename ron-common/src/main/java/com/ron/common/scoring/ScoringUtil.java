package com.ron.common.scoring;

public final class ScoringUtil {

    private ScoringUtil() {}

    public static int calculateWinPoints(int winnerScore, int loserScore) {
        int base = 25;
        // Bonus up to +25 extra if opponent had higher score
        int diff = loserScore - winnerScore;
        int bonus = 0;
        if (diff > 0) {
            bonus = Math.min(25, diff / 4);
        }
        int total = base + bonus;
        // Minimum +10 even if beating a lower ranked player
        return Math.max(10, total);
    }

    /**
     * Returns the unclamped penalty. Caller is responsible for clamping the resulting score >= 0
     * (PlayerStatsDAO.recordLoss does this).
     */
    public static int calculateLossPoints(int loserScore, int winnerScore) {
        int base = 15;
        int diff = winnerScore - loserScore;
        if (diff > 200) {
            base = 5;
        } else if (diff > 100) {
            base = 10;
        }
        return base;
    }

    public static String getRank(int points) {
        if (points >= 1000) return "Diamond";
        if (points >= 500) return "Platinum";
        if (points >= 250) return "Gold";
        if (points >= 100) return "Silver";
        return "Bronze";
    }

}
