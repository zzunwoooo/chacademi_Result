package school.chacademia.kyudichi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable snapshot of config.yml. A running match keeps the Rules it started with. Times are ticks, speeds blocks/tick. */
final class Rules {
    final int prepareTicks, countdownTicks, goalPauseTicks, playTicks, overtimeTicks, snitchAtTicks, respawnTicks, respawnInvulTicks;
    /** A deflected bludger flies on, then rests this long before it hunts anyone again. */
    final int deflectRestTicks;
    /** owner 2026-10-20: bludger charges (approach → telegraph → straight dash that flies on through) and wall margins */
    final double approachSpeed, dashSpeed, dashRange, dashOvershoot, edgeMargin, edgeMarginY; final int windupTicks, dashRecoverTicks;
    final GameMode gameMode;
    final int goalPoints, killPoints, snitchPoints, killCreditTicks;
    final double keyRange; final int keyCooldownTicks;
    final double quaffleDrift, throwSpeed, gravity; final int flightTicks, holdTicks, rethrowBlockTicks;
    final double autoCatchRadius, dropPop;
    final int bludgerCount; final double bludgerWander, bludgerChase, bludgerSteer, bludgerHitRadius, bludgerDamagePercent, bludgerKnockback, idleTargetRadius, deflectSpeed;
    final int ignoreAfterHitTicks, secondDelayTicks, deflectTicks;
    final double snitchSpeed, snitchFlee, snitchCatch; final int pauseMin, pauseMax, pauseTicks;
    final double shrineRadius, healthPercent, manaPercent, shrineScale; final int shrineRespawnTicks;
    final Map<ShrineType, String> shrineVfx;
    final RatingMath rating;
    final MvpWeights mvp;
    final Set<String> bannedSpells, bannedArtifacts;
    /** 1:1 결투 (owner 2026-10-18): 3판 2선승, 라운드 3분 (시간 끝 = 남은 체력 % 높은 쪽, 같으면 둘 다 1점), 비행 금지. */
    final int duelWins, duelRoundTicks, duelPrepareTicks, duelRoundPauseTicks;
    final Set<String> duelBannedSpells;
    final boolean teleportGuard;
    final Map<BallKind, String> models; final double modelScale; final Map<BallKind, Material> fallback;

    Rules(ConfigurationSection c) {
        prepareTicks = sec(c, "match.prepare-seconds", 60, 0, 600);
        countdownTicks = sec(c, "match.countdown-seconds", 3, 0, 30);
        goalPauseTicks = sec(c, "match.goal-pause-seconds", 2, 0, 30);
        playTicks = sec(c, "match.play-seconds", 600, 10, 7200);
        overtimeTicks = sec(c, "match.overtime-seconds", 180, 0, 3600);
        snitchAtTicks = sec(c, "match.snitch-at-seconds", 300, 0, 7200);
        respawnTicks = sec(c, "match.respawn-seconds", 5, 0, 60);
        respawnInvulTicks = sec(c, "match.respawn-invulnerable-seconds", 2, 0, 30);
        deflectRestTicks = secD(c, "bludger.after-deflect-rest-seconds", 2, 0, 20);
        approachSpeed = speed(c, "bludger.approach-speed", 18); dashSpeed = speed(c, "bludger.dash-speed", 44);
        dashRange = num(c, "bludger.dash-range", 14, 3, 64); dashOvershoot = num(c, "bludger.dash-overshoot", 10, 0, 64);
        windupTicks = secD(c, "bludger.windup-seconds", 0.4, 0, 3); dashRecoverTicks = secD(c, "bludger.dash-recover-seconds", 1.2, 0, 10);
        edgeMargin = num(c, "ball-edge-margin", 8, 0, 40); edgeMarginY = num(c, "ball-edge-margin-y", 3, 0, 40);
        GameMode gm;
        try { gm = GameMode.valueOf(c.getString("match.game-mode", "ADVENTURE").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { gm = GameMode.ADVENTURE; }
        gameMode = gm == GameMode.SPECTATOR ? GameMode.ADVENTURE : gm;
        goalPoints = c.getInt("score.goal", 10); killPoints = c.getInt("score.kill", 3); snitchPoints = c.getInt("score.snitch", 100);
        killCreditTicks = sec(c, "score.kill-credit-seconds", 10, 1, 60);
        keyRange = num(c, "key.range", 5, 0.5, 10); keyCooldownTicks = secD(c, "key.cooldown-seconds", 1, 0, 10);
        quaffleDrift = speed(c, "quaffle.drift-speed", 12); throwSpeed = speed(c, "quaffle.throw-speed", 30);
        gravity = num(c, "quaffle.gravity", 6, 0, 60) / 400.0;
        flightTicks = secD(c, "quaffle.flight-seconds", 3, 0.5, 20); holdTicks = sec(c, "quaffle.hold-seconds", 10, 1, 120);
        autoCatchRadius = num(c, "quaffle.auto-catch-radius", 1.5, 0.3, 5); rethrowBlockTicks = secD(c, "quaffle.rethrow-block-seconds", 0.5, 0, 5);
        dropPop = speed(c, "quaffle.drop-pop-speed", 6);
        bludgerCount = (int) num(c, "bludger.count", 2, 0, 2);
        bludgerWander = speed(c, "bludger.wander-speed", 6); bludgerChase = speed(c, "bludger.chase-speed", 16);
        bludgerSteer = num(c, "bludger.steer", 2.5, 0.1, 20) / 20.0;
        bludgerHitRadius = num(c, "bludger.hit-radius", 1.0, 0.3, 5); bludgerDamagePercent = num(c, "bludger.damage-percent", 25, 0, 100);
        bludgerKnockback = num(c, "bludger.knockback", 1.2, 0, 5);
        ignoreAfterHitTicks = secD(c, "bludger.ignore-after-hit-seconds", 2, 0, 30);
        idleTargetRadius = num(c, "bludger.idle-target-radius", 8, 0, 64);
        secondDelayTicks = secD(c, "bludger.second-delay-seconds", 1, 0, 10);
        deflectSpeed = speed(c, "bludger.deflect-speed", 24); deflectTicks = secD(c, "bludger.deflect-seconds", 1.5, 0.2, 10);
        snitchSpeed = speed(c, "snitch.speed", 12); snitchFlee = num(c, "snitch.flee-radius", 16, 0, 64); snitchCatch = num(c, "snitch.catch-range", 2, 0.5, 6);
        List<Integer> pe = c.getIntegerList("snitch.pause-every-seconds");
        pauseMin = 20 * (pe.size() > 0 ? Math.max(1, pe.get(0)) : 6); pauseMax = Math.max(pauseMin, 20 * (pe.size() > 1 ? pe.get(1) : 10));
        pauseTicks = secD(c, "snitch.pause-seconds", 1.2, 0, 10);
        shrineRadius = num(c, "shrine.pickup-radius", 1.6, 0.5, 6); shrineScale = num(c, "shrine.scale", 0.75, 0.2, 3); shrineRespawnTicks = sec(c, "shrine.respawn-seconds", 20, 1, 600);
        healthPercent = num(c, "shrine.health-percent", 50, 0, 100); manaPercent = num(c, "shrine.mana-percent", 50, 0, 100);
        var vfx = new TreeMap<ShrineType, String>();
        for (ShrineType t : ShrineType.values()) {
            String id = c.getString("shrine.vfx." + t.key, "shrine" + t.key).toLowerCase(Locale.ROOT);
            vfx.put(t, id.matches("[a-z0-9_.-]{1,64}") ? id : "shrine" + t.key);
        }
        shrineVfx = Map.copyOf(vfx);
        rating = new RatingMath(
                c.getDouble("rating.start", 1000), c.getDouble("rating.k", 60), c.getDouble("rating.min-change", 5),
                c.getDouble("rating.contribution-min", 0.8), c.getDouble("rating.contribution-max", 1.3),
                c.getDouble("rating.loss-max", 1.2), c.getDouble("rating.loss-min", 0.7), c.getDouble("rating.leaver-multiplier", 1.2),
                new int[]{c.getInt("rating.tiers.silver", 900), c.getInt("rating.tiers.gold", 1100), c.getInt("rating.tiers.platinum", 1300),
                        c.getInt("rating.tiers.diamond", 1500), c.getInt("rating.tiers.master", 1700)});
        mvp = new MvpWeights(c.getDouble("mvp.goal", 10), c.getDouble("mvp.kill", 3), c.getDouble("mvp.snitch", 30), c.getDouble("mvp.assist", 1),
                c.getDouble("mvp.carrier-stop-bonus", 2), c.getDouble("mvp.pass", 1), c.getDouble("mvp.save-goal", 3), c.getDouble("mvp.save-bludger", 2),
                c.getDouble("mvp.deflect-hit", 2), Math.max(1, c.getDouble("mvp.heal-per", 10)), Math.max(1, c.getDouble("mvp.damage-per", 20)),
                c.getDouble("mvp.save-goal-radius", 8));
        bannedSpells = lower(c.getStringList("banned-spells"));
        bannedArtifacts = lower(c.getStringList("banned-artifacts"));
        duelWins = (int) num(c, "duel.wins", 2, 1, 5);
        duelRoundTicks = sec(c, "duel.round-seconds", 180, 20, 1800);
        duelPrepareTicks = sec(c, "duel.prepare-seconds", 30, 0, 600);
        duelRoundPauseTicks = sec(c, "duel.round-pause-seconds", 3, 1, 30);
        var db = new java.util.HashSet<>(lower(c.getStringList("duel.banned-spells"))); db.add("flight");
        duelBannedSpells = Set.copyOf(db);
        teleportGuard = c.getBoolean("teleport-guard", true);
        var m = new TreeMap<BallKind, String>(); var f = new TreeMap<BallKind, Material>();
        for (BallKind k : BallKind.values()) {
            m.put(k, c.getString("models." + k.key, "kyudichi_" + k.key));
            Material mat = Material.matchMaterial(c.getString("fallback-items." + k.key, k.fallback.name()));
            f.put(k, mat == null || !mat.isItem() ? k.fallback : mat);
        }
        models = Map.copyOf(m); fallback = Map.copyOf(f);
        modelScale = num(c, "models.scale", 1.4, 0.1, 5);
    }

    private static Set<String> lower(List<String> list) {
        return Set.copyOf(list.stream().map(s -> s.toLowerCase(Locale.ROOT).trim()).filter(s -> !s.isEmpty()).toList());
    }
    private static double num(ConfigurationSection c, String k, double d, double min, double max) {
        double v = c.getDouble(k, d); return Double.isFinite(v) ? Math.clamp(v, min, max) : d;
    }
    private static double speed(ConfigurationSection c, String k, double d) { return num(c, k, d, 0, 120) / 20.0; }
    private static int sec(ConfigurationSection c, String k, int d, int min, int max) { return 20 * (int) num(c, k, d, min, max); }
    private static int secD(ConfigurationSection c, String k, double d, double min, double max) { return (int) Math.round(20 * num(c, k, d, min, max)); }

    record MvpWeights(double goal, double kill, double snitch, double assist, double carrierStop, double pass, double saveGoal,
                      double saveBludger, double deflectHit, double healPer, double damagePer, double saveGoalRadius) {}
}
