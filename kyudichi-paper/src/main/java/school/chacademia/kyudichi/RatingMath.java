package school.chacademia.kyudichi;

/**
 * Elo with team averages (오너: 상대 평균이 높은데 이기면 더 많이) and an MVP-share multiplier (기여도).
 * Pure, no Bukkit: unit-tested in RatingMathTest.
 */
record RatingMath(double start, double k, double minChange, double winMin, double winMax, double lossMax, double lossMin,
                  double leaverMultiplier, int[] tierFloors) {
    enum Result { WIN, DRAW, LOSS }

    /** Expected score of a team with average `mine` against `theirs`. */
    static double expected(double mine, double theirs) { return 1 / (1 + Math.pow(10, (theirs - mine) / 400)); }

    /**
     * @param share this player's MVP points / team total (NaN or team total 0 → treated as an equal share)
     * @param teamSize number of players on the team at the end (for the equal share)
     */
    int change(double teamAvg, double oppAvg, Result result, double share, int teamSize, boolean leaver) {
        double e = expected(teamAvg, oppAvg);
        if (leaver) return (int) -Math.max(minChange, Math.round(k * e * leaverMultiplier));
        if (result == Result.DRAW) return 0;
        double equal = 1.0 / Math.max(1, teamSize);
        double s = Double.isFinite(share) ? Math.clamp(share, 0, 1) : equal;
        // t: 0 (did nothing) → 0.5 (equal share) → 1 (carried: twice an equal share or more)
        double t = Math.clamp(s / (2 * equal), 0, 1);
        if (result == Result.WIN) {
            double f = t < 0.5 ? lerp(winMin, 1, t / 0.5) : lerp(1, winMax, (t - 0.5) / 0.5);
            return (int) Math.max(minChange, Math.round(k * (1 - e) * f));
        }
        double f = t < 0.5 ? lerp(lossMax, 1, t / 0.5) : lerp(1, lossMin, (t - 0.5) / 0.5);
        return (int) -Math.max(minChange, Math.round(k * e * f));
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Tier by rating; challenger needs master AND a rank 1..3 (rank 0 = unranked). */
    Tier tier(double rating, int rank) {
        if (rating >= tierFloors[4] && rank >= 1 && rank <= 3) return Tier.values()[Tier.CHALLENGER_1.ordinal() + rank - 1];
        for (int i = tierFloors.length - 1; i >= 0; i--) if (rating >= tierFloors[i]) return Tier.values()[i + 1];
        return Tier.BRONZE;
    }

    enum Tier {
        BRONZE("브론즈", "bronze"), SILVER("실버", "silver"), GOLD("골드", "gold"), PLATINUM("플래티넘", "platinum"),
        DIAMOND("다이아몬드", "diamond"), MASTER("마스터", "master"),
        CHALLENGER_1("챌린저 1", "challenger_1"), CHALLENGER_2("챌린저 2", "challenger_2"), CHALLENGER_3("챌린저 3", "challenger_3");
        final String label, icon;
        Tier(String label, String icon) { this.label = label; this.icon = icon; }
    }
}
