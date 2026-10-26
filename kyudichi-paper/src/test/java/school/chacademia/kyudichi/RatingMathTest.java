package school.chacademia.kyudichi;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RatingMathTest {
    static final RatingMath R = new RatingMath(1000, 60, 5, 0.8, 1.3, 1.2, 0.7, 1.2, new int[]{900, 1100, 1300, 1500, 1700});

    @Test void evenMatchIsThirty() {
        assertEquals(30, R.change(1000, 1000, RatingMath.Result.WIN, 1.0 / 3, 3, false));
        assertEquals(-30, R.change(1000, 1000, RatingMath.Result.LOSS, 1.0 / 3, 3, false));
        assertEquals(0, R.change(1000, 1000, RatingMath.Result.DRAW, 1.0 / 3, 3, false));
    }

    @Test void upsetPaysMore() {
        assertEquals(46, R.change(1000, 1200, RatingMath.Result.WIN, 1.0 / 3, 3, false));
        assertEquals(14, R.change(1200, 1000, RatingMath.Result.WIN, 1.0 / 3, 3, false));
    }

    @Test void contributionScalesTheChange() {
        int carry = R.change(1000, 1000, RatingMath.Result.WIN, 0.8, 3, false), idle = R.change(1000, 1000, RatingMath.Result.WIN, 0, 3, false);
        assertEquals(39, carry); assertEquals(24, idle);
        assertEquals(-21, R.change(1000, 1000, RatingMath.Result.LOSS, 0.8, 3, false));
        assertEquals(-36, R.change(1000, 1000, RatingMath.Result.LOSS, 0, 3, false));
        assertEquals(30, R.change(1000, 1000, RatingMath.Result.WIN, Double.NaN, 3, false));
    }

    @Test void minimumChangeAndLeaver() {
        assertEquals(5, R.change(2400, 1000, RatingMath.Result.WIN, 0, 3, false));
        assertEquals(-5, R.change(1000, 2400, RatingMath.Result.LOSS, 1, 3, false));
        assertEquals(-36, R.change(1000, 1000, RatingMath.Result.WIN, 1, 3, true));
    }

    @Test void tiers() {
        assertEquals(RatingMath.Tier.BRONZE, R.tier(899, 0));
        assertEquals(RatingMath.Tier.SILVER, R.tier(1000, 0));
        assertEquals(RatingMath.Tier.GOLD, R.tier(1100, 0));
        assertEquals(RatingMath.Tier.PLATINUM, R.tier(1499, 0));
        assertEquals(RatingMath.Tier.DIAMOND, R.tier(1500, 0));
        assertEquals(RatingMath.Tier.MASTER, R.tier(1700, 0));
        assertEquals(RatingMath.Tier.CHALLENGER_2, R.tier(1800, 2));
        assertEquals(RatingMath.Tier.GOLD, R.tier(1200, 1), "challenger needs master");
    }
}
