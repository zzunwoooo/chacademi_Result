package school.chacademia.kyudichi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;

/** One participant. All fields are touched on the main thread only. */
final class MatchPlayer {
    final UUID id; final String nick; final Team team; final int slot;
    final double ratingBefore;
    // where they came from (restored at the end)
    Location returnTo; GameMode returnMode;
    // live state
    boolean online = true, ready, left;
    boolean neverArrived;                   // matched over the network but never reached the arena (no leaver penalty, not recorded)
    long leftAt = -1;                       // match tick of the disconnect (reconnect grace)
    long deadUntil = -1;                    // > now while waiting to respawn
    long invulnerableUntil;
    long keyCooldownUntil;
    long rethrowBlockUntil;
    UUID lastDamager; long lastDamagedAt = -100000;
    final Map<UUID, Long> damagedBy = new HashMap<>();   // enemy → last tick (assists)
    Location lastSafe;
    int lastKeyAction = -1;
    int house = -1; String broom = "";   // 준비 화면 (ROSTER)
    org.bukkit.util.Vector aim; long aimAt = -1000;   // 공중 조준점 (C2S AIM), 바로 뒤 시전 하나 · F 던지기/쳐내기 하나에만
    org.bukkit.util.Vector lastBody, motion = new org.bukkit.util.Vector();   // body movement per tick (bludger lead)
    // stats
    int goals, kills, deaths, assists, snitch, passes, carrierStops, saveGoal, saveBludger, deflectHits;
    double damage, heal;
    double mvpPoints;
    boolean mvp;

    MatchPlayer(UUID id, String nick, Team team, int slot, double ratingBefore) {
        this.id = id; this.nick = nick; this.team = team; this.slot = slot; this.ratingBefore = ratingBefore;
    }

    boolean dead(long now) { return deadUntil > now; }
    boolean active(long now) { return online && !left && !dead(now); }

    double mvpScore(Rules.MvpWeights w) {
        return goals * w.goal() + kills * w.kill() + snitch * w.snitch() + assists * w.assist() + carrierStops * w.carrierStop()
                + passes * w.pass() + saveGoal * w.saveGoal() + saveBludger * w.saveBludger() + deflectHits * w.deflectHit()
                + heal / w.healPer() + damage / w.damagePer();
    }
}
