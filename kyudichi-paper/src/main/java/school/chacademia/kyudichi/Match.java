package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import school.chacademia.kyudichi.Ball.Bludger;
import school.chacademia.kyudichi.Ball.Quaffle;
import school.chacademia.kyudichi.Ball.Snitch;
import school.magiccodex.protocol.KyudichiProtocol;

/**
 * One 큐디치 match in one arena. Everything runs on the main thread from {@link #tick()} (every server tick) and the
 * event listener. Time counts in match ticks ({@link #now}); the play clock stops during goal pauses and line-ups.
 */
final class Match {
    enum Phase { PREPARE, COUNTDOWN, PLAY, GOAL_PAUSE, ENDED }
    enum EndReason { TIME, SNITCH, GOLDEN_GOAL, FORFEIT, ABANDONED, ADMIN, VOID, ROUNDS }

    /** Test / admin options. `rated` false = no rating or stats written. */
    record Options(boolean rated, boolean test, int prepareTicks, int playTicks, int snitchAtTicks, boolean bludgers, Set<UUID> invulnerable) {}

    static final int FORFEIT_MIN_TICKS = 3 * 60 * 20, FORFEIT_WINDOW = 15 * 20, FORFEIT_RETRY = 60 * 20, RECONNECT_TICKS = 60 * 20;

    final KyudichiPlugin plugin; final Arena arena; final Rules rules; final Options opt; final World world;
    /** 1:1 결투 (arena.duel): no balls, rounds instead of goals (score = rounds won), a kill ends the round. */
    final boolean duel; int round;
    final Map<UUID, MatchPlayer> players = new LinkedHashMap<>();
    final Set<UUID> spectators = new HashSet<>();
    final Random random = new Random();
    Phase phase = Phase.PREPARE;
    long now;
    int phaseLeft, playLeft, elapsed;
    boolean overtime, snitchCaught, started;
    final int[] score = new int[2];
    Quaffle quaffle; final List<Bludger> bludgers = new ArrayList<>(); Snitch snitch;
    final Map<ShrineType, ShrineSlot> shrines = new EnumMap<>(ShrineType.class);
    final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    Team voteTeam; long voteUntil; final Set<UUID> voteYes = new HashSet<>(); final Map<Team, Long> voteRetryAt = new EnumMap<>(Team.class);
    boolean ourTeleport;
    final UUID id = UUID.randomUUID();

    static final class ShrineSlot {
        final ShrineType type; Point at, lastPoint; UUID vfx; long respawnAt = -1;
        ShrineSlot(ShrineType type) { this.type = type; }
        boolean active() { return at != null; }
    }

    Match(KyudichiPlugin plugin, Arena arena, Rules rules, Options opt, World world) {
        this.plugin = plugin; this.arena = arena; this.rules = rules; this.opt = opt; this.world = world;
        this.duel = arena.duel;
        playLeft = opt.playTicks();
    }

    MatchPlayer add(Player p, Team team, double rating) {
        int slot = (int) players.values().stream().filter(m -> m.team == team).count();
        var mp = new MatchPlayer(p.getUniqueId(), plugin.hooks().nick(p), team, Math.min(2, slot), rating);
        mp.returnTo = p.getLocation(); mp.returnMode = p.getGameMode();
        mp.house = plugin.hooks().house(p); mp.broom = plugin.hooks().broomId(p);
        players.put(p.getUniqueId(), mp);
        p.showBossBar(bar);
        return mp;
    }

    /** A matched player who has not arrived (network match): counts as disconnected from the first tick. */
    MatchPlayer addAbsent(UUID id, String nick, Team team, double rating) {
        int slot = (int) players.values().stream().filter(m -> m.team == team).count();
        var mp = new MatchPlayer(id, nick, team, Math.min(2, slot), rating);
        mp.online = false; mp.leftAt = 0; mp.neverArrived = true;
        players.put(id, mp);
        return mp;
    }

    Player online(MatchPlayer m) { Player p = Bukkit.getPlayer(m.id); return p != null && p.isOnline() && m.online && !m.left ? p : null; }
    List<Player> onlinePlayers() { var out = new ArrayList<Player>(); for (var m : players.values()) { Player p = online(m); if (p != null) out.add(p); } return out; }
    MatchPlayer get(UUID id) { return players.get(id); }
    boolean frozen() { return phase == Phase.PREPARE || phase == Phase.COUNTDOWN; }
    /** 커스텀 경기 (#62): 관리자가 정한 레이팅 없는 경기 (테스트·연습 제외). 관전 인원 제한이 없다. */
    boolean custom() { return !opt.rated() && !opt.test(); }
    boolean live() { return phase == Phase.PLAY; }

    // ================================================================== lifecycle
    void start() {
        started = true;
        for (var m : players.values()) {
            Player p = online(m); if (p == null) continue;
            prepare(p, m);
            Point pre = arena.prepare.get(m.team);
            tp(p, (pre != null ? pre : arena.starts.get(m.team)[m.slot]).location());
            p.sendMessage(duel ? Component.text("1:1 대전 " + arena.name + " · " + rules.duelWins + "판 선승 · 라운드 " + rules.duelRoundTicks / 1200 + "분 · 준비 시간에 마법을 세팅하세요. (비행 금지)", m.team.color)
                    : Component.text("큐디치 " + arena.name + " · " + m.team.label + " · 준비 시간에 마법을 세팅하세요. (F: 공 잡기·던지기·블러저 쳐내기)", m.team.color));
        }
        if (!duel) spawnBalls();
        phaseLeft = opt.prepareTicks();
        if (phaseLeft <= 0) lineup(); else broadcastTitle(Component.text("경기 준비", NamedTextColor.GOLD), Component.text("마법 6칸을 세팅하세요"));
        sendMatchAll();
        sendRoster();
        sendShrines();
    }

    private void prepare(Player p, MatchPlayer m) {
        p.setGameMode(rules.gameMode);
        heal(p);
        p.setFireTicks(0); p.setFallDistance(0);
        m.lastSafe = null;
    }

    private void heal(Player p) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20); p.setSaturation(10);
    }

    /** Everyone to the start points, balls back home, countdown. Used at the start and after every goal. */
    void lineup() {
        phase = Phase.COUNTDOWN; phaseLeft = Math.max(1, rules.countdownTicks);
        for (var m : players.values()) {
            Player p = online(m); if (p == null) continue;
            if (m.dead(now)) { m.deadUntil = -1; p.setGameMode(rules.gameMode); heal(p); }
            plugin.hooks().stopBroomIfRiding(p);
            tp(p, arena.starts.get(m.team)[m.slot].location());
            p.setVelocity(new Vector());
            // 1:1: every round starts full (체력·마나)
            if (duel) { heal(p); p.setFireTicks(0); for (var e : p.getActivePotionEffects()) p.removePotionEffect(e.getType()); plugin.hooks().restoreMana(p, 100); }
        }
        if (duel) { round++; playLeft = rules.duelRoundTicks; broadcastTitle(Component.text("라운드 " + round, NamedTextColor.GOLD), Component.text(score[0] + " : " + score[1])); }
        else resetBalls();
        sendMatchAll();
    }

    private void startPlay() {
        phase = Phase.PLAY;
        if (shrines.isEmpty()) for (ShrineType t : ShrineType.values()) {
            if (duel && t != ShrineType.HEALTH && t != ShrineType.MANA) continue;   // 1:1: 체력·마나 성소만
            var s = new ShrineSlot(t); shrines.put(t, s); spawnShrine(s, null);
        }
        broadcastTitle(Component.text("시작!", NamedTextColor.GOLD), Component.empty());
        // kick-off: the quaffle is tossed up and then falls (gravity)
        if (quaffle != null && quaffle.state == Quaffle.State.FREE) quaffle.vel = new Vector(0, rules.throwSpeed * 0.35, 0);
        Sounds.whistle(this);
        sendMatchAll();
    }

    void tick() {
        if (phase == Phase.ENDED) return;
        now++;
        respawns();
        reconnectTimeouts();
        switch (phase) {
            case PREPARE -> {
                if (--phaseLeft <= 0 || allReady()) lineup();
            }
            case COUNTDOWN -> {
                if (phaseLeft % 20 == 0 && phaseLeft > 0) broadcastTitle(Component.text(String.valueOf(phaseLeft / 20), NamedTextColor.GOLD), Component.empty());
                if (--phaseLeft <= 0) startPlay();
                moveVisuals();
            }
            case GOAL_PAUSE -> { if (--phaseLeft <= 0) lineup(); moveVisuals(); }
            case PLAY -> play();
            default -> { }
        }
        if (phase == Phase.ENDED) return;
        if (now % 5 == 0) { updateBar(); keepInBounds(); }
        if (now % 2 == 0) keyStates();
        if (now % 4 == 0) sendBalls();
        if (now % 20 == 0) sendMatchAll();
        if (voteTeam != null && now >= voteUntil) voteFailed();
    }

    private void play() {
        elapsed++;
        if (duel) {
            if (--playLeft <= 0) { roundTimeUp(); return; }
            shrinesTick();
            return;
        }
        if (--playLeft <= 0) {
            if (overtime) { end(score[0] == score[1] ? null : (score[0] > score[1] ? Team.A : Team.B), EndReason.TIME); return; }
            if (score[0] == score[1] && rules.overtimeTicks > 0) {
                overtime = true; playLeft = rules.overtimeTicks;
                broadcastTitle(Component.text("골든골 연장", NamedTextColor.GOLD), Component.text("먼저 득점하는 팀이 승리"));
                feed(4, 0, "", "", 0);
            } else { end(score[0] == score[1] ? null : (score[0] > score[1] ? Team.A : Team.B), EndReason.TIME); return; }
        }
        for (var m : players.values()) {
            Player p = m.active(now) ? online(m) : null;
            if (p == null) { m.lastBody = null; m.motion = new Vector(); continue; }
            Vector b = body(p);
            m.motion = m.lastBody == null ? new Vector() : b.clone().subtract(m.lastBody);
            m.lastBody = b;
            if (!duel) {
                plugin.hooks().clearCooldown(p, "flight"); // 큐디치: no flight cooldown
                // owner 2026-10-20: no 낙마 in 큐디치 — balance 0 hangs the player on the broom until it recovers (motion mod).
                // Re-sent every 2 s so a relog / respawn / late mod start still gets it (1 byte).
                if (Math.floorMod(now + m.id.hashCode(), 40) == 0) plugin.hooks().broomHangMode(p, true);
            }
            if (phase != Phase.PLAY) return;
        }
        if (snitch == null && !snitchCaught && elapsed >= opt.snitchAtTicks()) spawnSnitch();
        quaffleTick();
        if (phase != Phase.PLAY) return;
        for (Bludger b : bludgers) bludgerTick(b);
        if (snitch != null) snitchTick();
        shrinesTick();
    }

    // ================================================================== balls
    private void spawnBalls() {
        quaffle = new Quaffle(arena.quaffle.vector());
        quaffle.visual = BallVisual.spawn(quaffle.location(world), BallKind.QUAFFLE, rules, plugin.getLogger());
        if (opt.bludgers()) for (int i = 0; i < Math.min(rules.bludgerCount, 2); i++) {
            Point at = arena.bludgers[i] != null ? arena.bludgers[i] : arena.quaffle;
            var b = new Bludger(i, at.vector());
            b.visual = BallVisual.spawn(b.location(world), BallKind.BLUDGER, rules, plugin.getLogger());
            bludgers.add(b);
        }
    }

    private void resetBalls() {
        if (quaffle.state == Quaffle.State.HELD) setHolder(null);
        quaffle.state = Quaffle.State.FREE; quaffle.pos = arena.quaffle.vector(); quaffle.vel = new Vector(); quaffle.thrower = null;
        for (Bludger b : bludgers) {
            Point at = arena.bludgers[b.index] != null ? arena.bludgers[b.index] : arena.quaffle;
            b.pos = at.vector(); b.vel = new Vector(); b.state = Bludger.State.FREE; b.target = null; b.ignore.clear();
        }
        if (snitch != null) { snitch.pos = snitchSpawn(); snitch.vel = new Vector(); }
        moveVisuals();
    }

    private void moveVisuals() {
        if (quaffle != null && quaffle.state != Quaffle.State.HELD) quaffle.visual.move(quaffle.location(world));
        for (Bludger b : bludgers) b.visual.move(b.location(world));
        if (snitch != null) snitch.visual.move(snitch.location(world));
    }

    private void quaffleTick() {
        var q = quaffle;
        switch (q.state) {
            case HELD -> {
                MatchPlayer h = players.get(q.holder); Player hp = h == null ? null : online(h);
                if (hp == null || h.dead(now)) { dropQuaffle(null, null); return; }
                Location eye = hp.getEyeLocation();
                Vector right = eye.getDirection().crossProduct(new Vector(0, 1, 0));
                if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
                q.prev = q.pos; q.pos = eye.toVector().add(right.normalize().multiply(0.45)).add(new Vector(0, -0.55, 0));
                q.visual.move(q.location(world));
                // goal: the carrier flies through the enemy goal (들고 통과해도 인정)
                Team defender = goalHit(q.prev, hp.getLocation().toVector().add(new Vector(0, 0.9, 0)));
                if (defender != null && defender != h.team) { goal(h, false); return; }
                if (now - q.heldSince >= rules.holdTicks) {
                    ChacaLink.notify(hp, "warning", "kyudichi:hold", "퀘이플을 놓쳤어요", "10초가 지났어요", 1500);
                    dropQuaffle(null, null);
                }
            }
            case FLYING -> {
                // owner 2026-10-20: a thrown quaffle flies in a straight line (no gravity) for the pass time
                q.step(world, arena.ballBox(), 0.45);
                Team defender = goalHit(q.prev, q.pos);
                MatchPlayer thrower = players.get(q.thrower);
                if (defender != null && thrower != null && defender != q.throwerTeam) { goal(thrower, true); return; }
                // pass: a teammate's body touches the ball
                for (var m : players.values()) {
                    if (m.team != q.throwerTeam || !m.active(now) || (m.id.equals(q.thrower) && now < m.rethrowBlockUntil)) continue;
                    Player p = online(m); if (p == null) continue;
                    if (q.sweptDistanceSquared(body(p)) <= rules.autoCatchRadius * rules.autoCatchRadius) {
                        if (!m.id.equals(q.thrower) && thrower != null) thrower.passes++;
                        catchQuaffle(m, p); return;
                    }
                }
                if (now - q.thrownAt >= rules.flightTicks) {
                    q.state = Quaffle.State.FREE; q.thrower = null;
                    // keeps going the way it was thrown, then slows into the free wander
                    q.steerTarget = q.vel.lengthSquared() > 1e-6 ? q.vel.clone().normalize().multiply(rules.quaffleDrift) : null;
                    q.wanderTimer = 30;
                }
                q.visual.move(q.location(world));
            }
            case FREE -> {
                // owner 2026-10-20: a loose quaffle flies around freely like a bludger (no gravity)
                if (--q.wanderTimer <= 0) {
                    q.wanderTimer = 40 + random.nextInt(30);
                    Vector d = Ball.randomDirection(random, 0.4);
                    if (q.grounded(world, arena.ballBox()) && d.getY() < 0) d.setY(-d.getY());
                    q.steerTarget = d.multiply(rules.quaffleDrift);
                }
                if (q.steerTarget != null) q.steer(q.avoidEdges(q.steerTarget, arena.ballBox(), rules.edgeMargin, rules.edgeMarginY, rules.quaffleDrift), rules.bludgerSteer * 0.5);
                q.step(world, arena.ballBox(), 0.6);
                q.visual.move(q.location(world));
            }
        }
    }

    /** Defending team of the goal crossed by segment a→b, or null. */
    private Team goalHit(Vector a, Vector b) {
        for (Team t : Team.values()) for (Box g : arena.goals.get(t)) if (g.segmentHits(a, b)) return t;
        return null;
    }

    private void catchQuaffle(MatchPlayer m, Player p) {
        quaffle.state = Quaffle.State.HELD; quaffle.thrower = null; quaffle.vel = new Vector();
        quaffle.heldSince = now;
        setHolder(m.id);
        Sounds.at(this, p.getLocation(), "catch", Sound.ENTITY_ITEM_PICKUP, 1, 1);
        ChacaLink.notify(p, "system", "kyudichi:catch", "퀘이플 소유!", "10초 안에 F로 던지세요 (소유 중 마법 사용 불가)", 1500);
    }

    private void setHolder(UUID id) {
        quaffle.holder = id;
        sendMatchAll();
    }

    /** Quaffle leaves its carrier (damage, 10 s rule, death, disconnect). `cause` = enemy who made it drop (stats). */
    void dropQuaffle(MatchPlayer cause, Vector push) {
        if (quaffle.state != Quaffle.State.HELD) return;
        MatchPlayer holder = players.get(quaffle.holder);
        setHolder(null);
        quaffle.state = Quaffle.State.FREE;
        Vector pop = push != null ? push.clone() : Ball.randomDirection(random, 0.2);
        pop.setY(Math.abs(pop.getY()) + 0.6);
        quaffle.vel = pop.normalize().multiply(rules.dropPop);
        quaffle.wanderTimer = 30;
        if (cause != null && holder != null && cause.team != holder.team) {
            cause.carrierStops++;
            if (nearOwnGoal(cause.team, quaffle.pos)) cause.saveGoal++;
        }
        world.spawnParticle(Particle.CRIT, quaffle.location(world), 12, 0.3, 0.3, 0.3, 0.1);
    }

    private boolean nearOwnGoal(Team team, Vector at) {
        double r = rules.mvp.saveGoalRadius();
        for (Box g : arena.goals.get(team)) if (g.center().distanceSquared(at) <= r * r) return true;
        return false;
    }

    private void throwQuaffle(MatchPlayer m, Player p) {
        quaffle.state = Quaffle.State.FLYING; setHolder(null);
        quaffle.thrower = m.id; quaffle.throwerTeam = m.team; quaffle.thrownAt = now;
        m.rethrowBlockUntil = now + rules.rethrowBlockTicks;
        Vector dir = aimDirection(m, p);
        quaffle.pos = p.getEyeLocation().toVector().add(dir.clone().multiply(0.8));
        quaffle.vel = dir.multiply(rules.throwSpeed).add(p.getVelocity().multiply(0.5));
        Sounds.at(this, p.getLocation(), "throw", Sound.ENTITY_SNOWBALL_THROW, 1, 1);
    }

    /**
     * Where F throws / hits: toward the crosshair point the client sent with the key press (C2S AIM, third-person / shoulder
     * camera aware); the body's look direction when there is none (no mod, or older than half a second).
     */
    private Vector aimDirection(MatchPlayer m, Player p) {
        Vector eye = p.getEyeLocation().toVector();
        if (m.aim != null && now - m.aimAt <= 10) {
            Vector d = m.aim.clone().subtract(eye); m.aim = null;
            if (d.lengthSquared() > 0.25) return d.normalize();
        }
        return p.getEyeLocation().getDirection().normalize();
    }

    private void bludgerTick(Bludger b) {
        b.ignore.values().removeIf(t -> t <= now);
        if (b.state == Bludger.State.DEFLECT) {
            b.step(world, arena.ballBox(), 0.6);
            b.visual.move(b.location(world));
            for (var m : players.values()) {
                if (m.team == b.deflectTeam || !m.active(now) || now < m.invulnerableUntil || opt.invulnerable().contains(m.id)) continue;
                Player p = online(m);
                if (p != null && b.sweptDistanceSquared(body(p)) <= rules.bludgerHitRadius * rules.bludgerHitRadius) {
                    MatchPlayer by = players.get(b.deflectBy);
                    if (by != null) by.deflectHits++;
                    bludgerHit(b, m, p, by);
                    endDeflect(b);
                    return;
                }
            }
            if (now >= b.deflectUntil) endDeflect(b);
            return;
        }
        Box box = arena.ballBox();
        if (b.state == Bludger.State.DASH) {
            // straight charge: no steering, hits whoever is in the way and keeps flying through (inertia)
            b.step(world, box, 0.6);
            b.visual.move(b.location(world));
            for (var m : players.values()) {
                if (!m.active(now) || b.ignore.containsKey(m.id) || now < m.invulnerableUntil || opt.invulnerable().contains(m.id)) continue;
                Player p = online(m);
                if (p != null && b.sweptDistanceSquared(body(p)) <= rules.bludgerHitRadius * rules.bludgerHitRadius) bludgerHit(b, m, p, null, true);
            }
            if (now >= b.dashUntil) {
                // overshoot done: drift on, slow down, then look for the next victim after a short rest
                b.state = Bludger.State.FREE; b.target = null; b.startAt = now + rules.dashRecoverTicks;
                b.steerTarget = b.vel.lengthSquared() > 1e-6 ? b.vel.clone().normalize().multiply(rules.bludgerWander) : null; b.wanderTimer = rules.dashRecoverTicks;
            }
            return;
        }
        Player target = null; MatchPlayer tm = b.target == null ? null : players.get(b.target);
        if (b.state == Bludger.State.WINDUP) {
            if (tm != null && tm.active(now)) target = online(tm);
            if (target == null) { b.state = Bludger.State.FREE; b.target = null; }
            else {
                // telegraph: almost stops and shudders, then launches at where the victim is going
                b.vel.multiply(0.75).add(Ball.randomDirection(random, 1).multiply(0.03));
                b.step(world, box, 0.6);
                b.visual.move(b.location(world));
                if (now >= b.windupUntil) {
                    double dist = body(target).distance(b.pos);
                    double lead = Math.min(12, dist / Math.max(0.05, rules.dashSpeed));
                    Vector to = body(target).add(tm.motion.clone().multiply(lead)).subtract(b.pos);
                    if (to.lengthSquared() < 1e-6) to = b.vel.lengthSquared() > 1e-6 ? b.vel.clone() : new Vector(1, 0, 0);
                    double len = to.length();
                    b.vel = to.multiply(rules.dashSpeed / len);
                    b.state = Bludger.State.DASH;
                    b.dashUntil = now + (long) Math.ceil((len + rules.dashOvershoot) / Math.max(0.05, rules.dashSpeed));
                    Sounds.at(this, b.location(world), "bludger_dash", Sound.ENTITY_BREEZE_SHOOT, 1.4f, 0.7f);
                }
                return;
            }
        }
        if (now % 10 == b.index * 5L) retarget(b);
        tm = b.target == null ? null : players.get(b.target);
        if (tm != null && tm.active(now)) target = online(tm);
        Vector desired;
        double speed;
        if (target != null && now >= b.startAt) {
            b.state = Bludger.State.CHASE;
            Vector to = body(target).subtract(b.pos);
            double d = to.length();
            if (d <= rules.dashRange) {
                b.state = Bludger.State.WINDUP; b.windupUntil = now + rules.windupTicks;
                Sounds.at(this, b.location(world), "bludger_windup", Sound.ENTITY_IRON_GOLEM_HURT, 0.8f, 0.6f);
                world.spawnParticle(Particle.SMOKE, b.location(world), 8, 0.3, 0.3, 0.3, 0.02);
                b.step(world, box, 0.7); b.visual.move(b.location(world));
                return;
            }
            // closes in at a moderate speed (not glued to the victim) until it is in charging range
            desired = d < 1e-6 ? new Vector() : to.multiply(rules.approachSpeed / d);
            speed = rules.approachSpeed;
            b.steer(b.avoidEdges(desired, box, rules.edgeMargin, rules.edgeMarginY, speed), rules.bludgerSteer);
        } else {
            b.state = Bludger.State.FREE;
            if (--b.wanderTimer <= 0) { b.wanderTimer = 40 + random.nextInt(30); b.steerTarget = Ball.randomDirection(random, 0.4).multiply(rules.bludgerWander); }
            if (b.steerTarget != null) b.steer(b.avoidEdges(b.steerTarget, box, rules.edgeMargin, rules.edgeMarginY, rules.bludgerWander), rules.bludgerSteer * 0.5);
        }
        b.step(world, box, 0.7);
        b.visual.move(b.location(world));
    }

    /** End of a deflection: it keeps drifting the way it was hit and only hunts again after a short rest (no sudden turn onto someone else). */
    private void endDeflect(Bludger b) {
        b.state = Bludger.State.FREE; b.target = null; b.startAt = now + rules.deflectRestTicks;
        b.steerTarget = b.vel.lengthSquared() > 1e-6 ? b.vel.clone().normalize().multiply(rules.bludgerWander) : null; b.wanderTimer = rules.deflectRestTicks;
    }

    private void retarget(Bludger b) {
        UUID old = b.target; b.target = null;
        if (quaffle.state == Quaffle.State.HELD && !b.ignore.containsKey(quaffle.holder)) {
            MatchPlayer h = players.get(quaffle.holder);
            if (h != null && h.active(now)) b.target = h.id;
        }
        // 악질: keeps hunting the same victim while they are in play and not far away, instead of switching to whoever is nearest
        if (b.target == null && old != null && !b.ignore.containsKey(old)) {
            MatchPlayer o = players.get(old); Player op = o == null ? null : online(o);
            double keep = Math.max(rules.idleTargetRadius, 8) * 1.5;
            if (o != null && op != null && o.active(now) && body(op).distanceSquared(b.pos) <= keep * keep) b.target = old;
        }
        if (b.target == null && rules.idleTargetRadius > 0) {
            double best = rules.idleTargetRadius * rules.idleTargetRadius;
            for (var m : players.values()) {
                if (!m.active(now) || b.ignore.containsKey(m.id)) continue;
                Player p = online(m); if (p == null) continue;
                double d = body(p).distanceSquared(b.pos);
                if (d < best) { best = d; b.target = m.id; }
            }
        }
        if (b.target != null && !b.target.equals(old)) {
            boolean shared = bludgers.stream().anyMatch(o -> o != b && b.target.equals(o.target));
            b.startAt = Math.max(b.startAt, shared ? now + rules.secondDelayTicks : now); // keeps an after-deflect rest
        }
    }

    private void bludgerHit(Bludger b, MatchPlayer victim, Player p, MatchPlayer credit) { bludgerHit(b, victim, p, credit, false); }
    /** passThrough: a charging bludger keeps its line (slightly slowed) instead of bouncing back. */
    private void bludgerHit(Bludger b, MatchPlayer victim, Player p, MatchPlayer credit, boolean passThrough) {
        b.ignore.put(victim.id, now + rules.ignoreAfterHitTicks);
        if (!passThrough) b.target = null;
        b.visual.animate("hit");
        var maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        double dmg = max * rules.bludgerDamagePercent / 100.0;
        Vector push = p.getLocation().toVector().subtract(b.pos);
        if (push.lengthSquared() < 1e-6) push = b.vel.clone();
        push = push.lengthSquared() < 1e-6 ? new Vector(0, 0.4, 0) : push.normalize().multiply(rules.bludgerKnockback).setY(0.35);
        Sounds.at(this, p.getLocation(), "bludger_hit", Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 1);
        world.spawnParticle(Particle.EXPLOSION, b.location(world), 1);
        if (passThrough) push = b.vel.lengthSquared() < 1e-6 ? push : b.vel.clone().normalize().multiply(rules.bludgerKnockback).setY(0.35);
        b.vel = passThrough ? b.vel.multiply(0.85) : b.vel.multiply(-0.4);
        if (credit != null) { recordDamage(credit, victim, dmg); }
        // fixed damage: ignores armour, artifacts and every reduction (오너 결정)
        if (p.getHealth() - dmg <= 0.0001) { kill(victim, p, credit); return; }
        p.setHealth(p.getHealth() - dmg);
        p.playHurtAnimation(0);
        p.setVelocity(p.getVelocity().add(push));
        damaged(victim, credit, push);
    }

    private void spawnSnitch() {
        snitch = new Snitch(snitchSpawn());
        snitch.visual = BallVisual.spawn(snitch.location(world), BallKind.SNITCH, rules, plugin.getLogger());
        snitch.visual.animate("spawn");
        snitch.nextPauseAt = now + rules.pauseMin + random.nextInt(Math.max(1, rules.pauseMax - rules.pauseMin + 1));
        broadcastTitle(Component.empty(), Component.text("스니치가 나타났어요!", NamedTextColor.GOLD));
        Sounds.all(this, "snitch_appear", Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1, 1);
        Sounds.all(this, "whistle_long", null, 0.8f, 1);
        sendMatchAll();
    }

    private Vector snitchSpawn() {
        if (!arena.snitchSpawns.isEmpty()) return arena.snitchSpawns.get(random.nextInt(arena.snitchSpawns.size())).vector();
        return arena.snitchBox().center();
    }

    /**
     * Owner 2026-10-17: much faster and freer. Flees anyone near (closer = stronger), jukes sideways in short dashes,
     * wanders the whole snitch box in 3D when nobody is near, and only rarely hovers for a moment (the chance to catch it).
     */
    private void snitchTick() {
        var s = snitch;
        double top = rules.snitchSpeed;
        if (now < s.pausedUntil) {
            s.vel.multiply(0.7);
            s.vel.add(Ball.randomDirection(random, 1).multiply(0.02));   // hovering flutter
        } else {
            if (now >= s.nextPauseAt) { s.pausedUntil = now + rules.pauseTicks; s.nextPauseAt = s.pausedUntil + rules.pauseMin + random.nextInt(Math.max(1, rules.pauseMax - rules.pauseMin + 1)); }
            Vector flee = new Vector(); int n = 0;
            double r2 = rules.snitchFlee * rules.snitchFlee;
            for (var m : players.values()) {
                if (!m.active(now)) continue;
                Player p = online(m); if (p == null) continue;
                // run from where the chaser is heading, not only where they are
                Vector away = s.pos.clone().subtract(body(p).add(m.motion.clone().multiply(6)));
                double d2 = away.lengthSquared();
                if (d2 < r2 && d2 > 1e-6) { flee.add(away.multiply(1 / d2)); n++; }
            }
            if (now >= s.nextDashAt) {
                Vector r = Ball.randomDirection(random, 0.8);
                // juke: with a chaser, dash across the chase line; otherwise anywhere
                s.dashDir = n > 0 && flee.lengthSquared() > 1e-9 ? flee.clone().normalize().crossProduct(r).add(flee.clone().normalize().multiply(0.4)) : r;
                if (s.dashDir.lengthSquared() < 1e-6) s.dashDir = r;
                s.dashDir.normalize();
                s.dashUntil = now + 6 + random.nextInt(9);
                s.nextDashAt = now + (n > 0 ? 12 + random.nextInt(20) : 25 + random.nextInt(40));
            }
            Vector desired;
            if (now < s.dashUntil) desired = s.dashDir.clone().multiply(top * 1.25);
            else if (n > 0) desired = flee.normalize().add(Ball.randomDirection(random, 0.8).multiply(0.6)).normalize().multiply(top);
            else {
                if (--s.wanderTimer <= 0) { s.wanderTimer = 15 + random.nextInt(25); s.steerTarget = Ball.randomDirection(random, 0.8).multiply(top * 0.6); }
                desired = s.steerTarget == null ? new Vector() : s.steerTarget;
            }
            // owner 2026-10-20: stays away from the arena walls so a chaser is not stopped by the invisible boundary
            desired = s.avoidEdges(desired, arena.snitchBox(), rules.edgeMargin, rules.edgeMarginY, top);
            s.steer(desired, now < s.dashUntil ? 0.5 : 0.3);
        }
        s.step(world, arena.snitchBox(), 0.9);
        s.visual.move(s.location(world));
    }

    // ================================================================== F key
    /** Action the F key would do now (wire KEY action). */
    int keyAction(MatchPlayer m, Player p) {
        if (duel || !live() || !m.active(now)) return 0;
        Vector at = body(p); double r2 = rules.keyRange * rules.keyRange;
        for (Bludger b : bludgers) if (!(b.state == Bludger.State.DEFLECT && b.deflectTeam == m.team) && b.pos.distanceSquared(at) <= r2) return 3;
        boolean holding = quaffle.state == Quaffle.State.HELD && m.id.equals(quaffle.holder);
        if (snitch != null && !holding && snitch.pos.distanceSquared(at) <= rules.snitchCatch * rules.snitchCatch) return 4;
        if (holding) return 2;
        if (quaffle.state != Quaffle.State.HELD && quaffle.pos.distanceSquared(at) <= r2 && !(m.id.equals(quaffle.thrower) && now < m.rethrowBlockUntil)) return 1;
        return 0;
    }

    void pressKey(Player p) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null || !live() || now < m.keyCooldownUntil) return;
        int action = keyAction(m, p);
        if (action == 0) return;
        m.keyCooldownUntil = now + rules.keyCooldownTicks;
        switch (action) {
            case 3 -> deflect(m, p);
            case 4 -> catchSnitch(m, p);
            case 2 -> throwQuaffle(m, p);
            case 1 -> catchQuaffle(m, p);
            default -> { }
        }
        keyState(m, p, true);
    }

    private void deflect(MatchPlayer m, Player p) {
        Vector at = body(p); Bludger best = null; double bd = Double.MAX_VALUE;
        for (Bludger b : bludgers) {
            if (b.state == Bludger.State.DEFLECT && b.deflectTeam == m.team) continue;
            double d = b.pos.distanceSquared(at); if (d < bd) { bd = d; best = b; }
        }
        if (best == null) return;
        MatchPlayer was = best.target == null ? null : players.get(best.target);
        if (was != null && was.team == m.team && !was.id.equals(m.id) && (best.state == Bludger.State.CHASE || best.state == Bludger.State.WINDUP || best.state == Bludger.State.DASH)) m.saveBludger++;
        best.state = Bludger.State.DEFLECT; best.deflectBy = m.id; best.deflectTeam = m.team; best.deflectUntil = now + rules.deflectTicks;
        best.target = null;
        best.vel = aimDirection(m, p).multiply(rules.deflectSpeed);
        best.visual.animate("hit");
        Sounds.at(this, best.location(world), "deflect", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 1);
        world.spawnParticle(Particle.SWEEP_ATTACK, best.location(world), 1);
    }

    private void catchSnitch(MatchPlayer m, Player p) {
        snitchCaught = true;
        m.snitch++;
        score[m.team.ordinal()] += rules.snitchPoints;
        snitch.visual.remove(); world.spawnParticle(Particle.END_ROD, snitch.location(world), 40, 0.4, 0.4, 0.4, 0.15);
        snitch = null;
        feed(3, m.team.ordinal(), m.nick, "", rules.snitchPoints);
        broadcastTitle(Component.text(m.nick + " 스니치 포획!", NamedTextColor.GOLD), Component.text("+" + rules.snitchPoints, NamedTextColor.YELLOW));
        Sounds.all(this, "goal", Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1.1f);
        Sounds.all(this, "whistle_end", null, 1, 1);
        end(score[0] == score[1] ? null : (score[0] > score[1] ? Team.A : Team.B), EndReason.SNITCH);
    }

    private void keyStates() {
        for (var m : players.values()) { Player p = online(m); if (p != null) keyState(m, p, false); }
    }

    private void keyState(MatchPlayer m, Player p, boolean force) {
        int action = keyAction(m, p);
        int cd = (int) Math.max(0, (m.keyCooldownUntil - now) * 50);
        boolean holding = quaffle != null && quaffle.state == Quaffle.State.HELD && m.id.equals(quaffle.holder);
        int hold = holding ? (int) Math.max(0, (rules.holdTicks - (now - quaffle.heldSince)) * 50) : 0;
        boolean mod = plugin.net().hasMod(p);
        if (mod) { if (force || action != m.lastKeyAction || holding || cd > 0) plugin.net().send(p, new KyudichiProtocol.Key(action, Math.min(cd, 65535), Math.min(hold, 65535))); }
        else if (action != 0 || holding) {
            String label = switch (action) { case 1 -> "퀘이플 잡기"; case 2 -> "퀘이플 던지기"; case 3 -> "블러저 쳐내기"; case 4 -> "스니치 잡기"; default -> ""; };
            var text = Component.text("[F] " + label, NamedTextColor.GOLD, TextDecoration.BOLD);
            if (holding) text = text.append(Component.text("  남은 " + (hold + 999) / 1000 + "초", NamedTextColor.WHITE));
            p.sendActionBar(text);
        } else if (m.lastKeyAction > 0) p.sendActionBar(Component.empty());
        m.lastKeyAction = action;
    }

    // ================================================================== shrines
    private void spawnShrine(ShrineSlot s, Point avoid) {
        var list = arena.shrines.get(s.type);
        if (list.isEmpty()) return;
        Point pick = list.get(random.nextInt(list.size()));
        if (list.size() > 1 && avoid != null) for (int i = 0; i < 4 && pick.equals(avoid); i++) pick = list.get(random.nextInt(list.size()));
        s.at = pick; s.respawnAt = -1;
        Location l = pick.location();
        if (l != null) s.vfx = plugin.hooks().playVfx(rules.shrineVfx.get(s.type), "idle", l, 12000, (float) rules.shrineScale);
        sendShrines();
    }

    private void shrinesTick() {
        double r2 = rules.shrineRadius * rules.shrineRadius;
        for (var s : shrines.values()) {
            if (!s.active()) { if (s.respawnAt >= 0 && now >= s.respawnAt) spawnShrine(s, s.lastPoint); continue; }
            if (now % 40 == 0 && s.vfx == null) world.spawnParticle(Particle.HAPPY_VILLAGER, s.at.location(), 6, 0.4, 0.6, 0.4, 0);
            Vector c = s.at.vector();
            for (var m : players.values()) {
                if (!m.active(now)) continue;
                Player p = online(m);
                if (p == null || body(p).distanceSquared(c) > r2) continue;
                takeShrine(s, m, p);
                break;
            }
        }
    }

    private void takeShrine(ShrineSlot s, MatchPlayer m, Player p) {
        switch (s.type) {
            case HEALTH -> {
                var a = p.getAttribute(Attribute.MAX_HEALTH); double max = a == null ? 20 : a.getValue();
                p.setHealth(Math.min(max, p.getHealth() + max * rules.healthPercent / 100.0));
            }
            case MANA -> plugin.hooks().restoreMana(p, rules.manaPercent);
            case STABILITY -> plugin.hooks().refillBroomBalance(p);
            case HASTE -> plugin.hooks().freeBroomBoost(p);
        }
        plugin.hooks().stopVfx(s.vfx); s.vfx = null;
        Location l = s.at.location();
        if (l != null) plugin.hooks().playVfx(rules.shrineVfx.get(s.type), "pickup", l, 30, (float) rules.shrineScale);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1, 1.5f);
        ChacaLink.notify(p, "system", "kyudichi:shrine", s.type.label + " 성소!", "", 1500);
        Point old = s.at; s.at = null; s.respawnAt = now + rules.shrineRespawnTicks;
        sendShrines();
        // next one appears elsewhere (if there is another candidate)
        s.lastPoint = old;
    }

    // ================================================================== scoring, damage, death
    private void goal(MatchPlayer scorer, boolean thrown) {
        score[scorer.team.ordinal()] += rules.goalPoints;
        scorer.goals++;
        quaffle.state = Quaffle.State.FREE; setHolder(null); quaffle.vel = new Vector();
        feed(1, scorer.team.ordinal(), scorer.nick, "", rules.goalPoints);
        broadcastTitle(Component.text(scorer.team.label + " 골!", scorer.team.color), Component.text(scorer.nick + " +" + rules.goalPoints, NamedTextColor.WHITE));
        Sounds.all(this, "goal", Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1, 1);
        Sounds.whistle(this);
        world.spawnParticle(Particle.FIREWORK, quaffle.location(world), 40, 0.6, 0.6, 0.6, 0.15);
        if (overtime) { end(scorer.team, EndReason.GOLDEN_GOAL); return; }
        phase = Phase.GOAL_PAUSE; phaseLeft = Math.max(1, rules.goalPauseTicks);
        sendMatchAll();
    }

    /** Spell/melee damage between players (from the listener, already filtered for friendly fire). */
    void recordDamage(MatchPlayer attacker, MatchPlayer victim, double amount) {
        if (attacker == null || attacker.team == victim.team) return;
        attacker.damage += amount;
        victim.lastDamager = attacker.id; victim.lastDamagedAt = now;
        victim.damagedBy.put(attacker.id, now);
    }

    /** Any damage that went through: drops the quaffle. */
    void damaged(MatchPlayer victim, MatchPlayer attacker, Vector push) {
        if (quaffle != null && quaffle.state == Quaffle.State.HELD && victim.id.equals(quaffle.holder)) dropQuaffle(attacker, push);
    }

    /** Lethal damage: no vanilla death; spectate in place, respawn after the delay. */
    void kill(MatchPlayer victim, Player p, MatchPlayer killer) {
        // a vanilla death handled a tick later (GameListener.death) can land after the end: never put a restored player in spectator
        if (phase == Phase.ENDED || victim.left || victim.dead(now)) return;
        if (killer == null && victim.lastDamager != null && now - victim.lastDamagedAt <= rules.killCreditTicks) killer = players.get(victim.lastDamager);
        if (duel) {
            // 1:1: a kill ends the round — no spectator time, both go back to their spawn points
            if (phase != Phase.PLAY) return;
            victim.deaths++;
            if (killer != null && killer.team != victim.team) { killer.kills++; feed(2, killer.team.ordinal(), killer.nick, victim.nick, 0); }
            else feed(2, victim.team.other().ordinal(), "", victim.nick, 0);
            victim.damagedBy.clear(); victim.lastDamager = null;
            heal(p);
            world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1, 1);
            roundWon(victim.team.other());
            return;
        }
        damaged(victim, killer, null);
        victim.deaths++;
        victim.deadUntil = now + Math.max(1, rules.respawnTicks);
        if (killer != null && killer.team != victim.team) {
            killer.kills++;
            score[killer.team.ordinal()] += rules.killPoints;
            if (quaffle != null && victim.id.equals(quaffle.holder)) killer.carrierStops++;
            for (var e : victim.damagedBy.entrySet()) {
                if (e.getKey().equals(killer.id) || now - e.getValue() > rules.killCreditTicks) continue;
                MatchPlayer a = players.get(e.getKey()); if (a != null && a.team != victim.team) a.assists++;
            }
            feed(2, killer.team.ordinal(), killer.nick, victim.nick, rules.killPoints);
        } else feed(2, victim.team.other().ordinal(), "", victim.nick, 0);
        victim.damagedBy.clear(); victim.lastDamager = null;
        plugin.hooks().stopBroomIfRiding(p);
        p.setGameMode(GameMode.SPECTATOR);
        p.showTitle(Title.title(Component.text("쓰러졌어요", NamedTextColor.RED), Component.text((rules.respawnTicks / 20) + "초 뒤 부활" + (killer != null ? " · " + killer.nick : ""))));
        world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1, 1);
        sendMatchAll();
    }

    // ================================================================== 1:1 rounds
    /** Round time is up: the higher remaining health % wins the round; equal = a point to both. */
    private void roundTimeUp() {
        double[] hp = {-1, -1};
        for (var m : players.values()) {
            Player p = online(m);
            double v = 0;
            if (p != null) { var max = p.getAttribute(Attribute.MAX_HEALTH); v = p.getHealth() / (max == null ? 20 : max.getValue()); }
            hp[m.team.ordinal()] = Math.max(hp[m.team.ordinal()], v);
        }
        Team w = Math.abs(hp[0] - hp[1]) < 1e-6 ? null : hp[0] > hp[1] ? Team.A : Team.B;
        broadcastTitle(Component.text("시간 종료", NamedTextColor.GRAY), Component.text(w == null ? "체력이 같아 둘 다 1점" : "남은 체력이 많은 " + nickOf(w) + " 승리"));
        roundWon(w);
    }

    private String nickOf(Team t) { for (var m : players.values()) if (m.team == t) return m.nick; return t.label; }

    /** `winner` null = both get the point. First to rules.duelWins ends the match (both at it = draw). */
    private void roundWon(Team winner) {
        if (winner == null) { score[0]++; score[1]++; } else score[winner.ordinal()]++;
        Sounds.whistle(this);
        if (winner != null) broadcastTitle(Component.text(nickOf(winner) + " 라운드 승리", winner.color), Component.text(score[0] + " : " + score[1]));
        boolean a = score[0] >= rules.duelWins, b = score[1] >= rules.duelWins;
        if (a || b) { end(a && b ? null : a ? Team.A : Team.B, EndReason.ROUNDS); return; }
        phase = Phase.GOAL_PAUSE; phaseLeft = rules.duelRoundPauseTicks;
        sendMatchAll();
    }

    private void respawns() {
        for (var m : players.values()) {
            if (m.deadUntil < 0 || m.deadUntil > now) continue;
            m.deadUntil = -1;
            Player p = online(m); if (p == null) continue;
            prepare(p, m);
            tp(p, arena.respawn(m.team, m.slot).location());
            m.invulnerableUntil = now + rules.respawnInvulTicks;
            sendMatchAll();
        }
    }

    // ================================================================== bounds
    private void keepInBounds() {
        Box b = arena.bounds;
        for (var m : players.values()) {
            Player p = online(m); if (p == null) continue;
            Location l = p.getLocation();
            if (!l.getWorld().equals(world)) continue;
            Vector v = l.toVector();
            boolean forbidden = arena.forbidden.stream().anyMatch(f -> f.contains(v));
            if (forbidden && m.lastSafe != null) { tp(p, m.lastSafe.clone().setDirection(l.getDirection())); p.setVelocity(new Vector()); continue; }
            Vector in = b.clampInside(v, 0.6);
            if (in != null) {
                Location to = new Location(world, in.getX(), in.getY(), in.getZ(), l.getYaw(), l.getPitch());
                tp(p, to);
                p.setVelocity(b.center().subtract(in).normalize().multiply(0.6));
                ChacaLink.notify(p, "warning", "kyudichi:bounds", "경기장 밖으로 나갈 수 없어요", "", 3000);
            } else if (!forbidden) m.lastSafe = l.clone();
        }
    }

    // ================================================================== disconnects, ready, forfeit
    void quit(Player p) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null) return;
        if (quaffle != null && m.id.equals(quaffle.holder)) dropQuaffle(null, null);
        m.online = false; m.leftAt = now;
        p.hideBossBar(bar);
        sendMatchAll();
    }

    boolean rejoin(Player p) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null || m.left || m.online) return false;
        m.online = true; m.leftAt = -1; m.neverArrived = false; m.deadUntil = -1;
        // a late arrival (network match) gets the same restore point as everyone else
        if (m.returnTo == null) { m.returnTo = p.getLocation(); m.returnMode = p.getGameMode(); }
        m.house = plugin.hooks().house(p); m.broom = plugin.hooks().broomId(p);
        p.showBossBar(bar);
        prepare(p, m);
        tp(p, (frozen() ? arena.starts.get(m.team)[m.slot] : arena.respawn(m.team, m.slot)).location());
        ChacaLink.notify(p, "system", "kyudichi:rejoin", "경기에 다시 들어왔어요", "", 1500);
        sendMatchAll();
        sendRoster();
        return true;
    }

    /**
     * Came back after the 60 s reconnect window (already counted as left): not put back in the match, but no longer kept
     * on the arena either — restored and sent back to their own server (network players) right away.
     */
    boolean lateReturn(Player p) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null || !m.left) return false;
        p.hideBossBar(bar);
        restore(p, m);
        ChacaLink.notify(p, "warning", "kyudichi:late", "경기에 다시 들어갈 수 없어요", "다시 접속할 수 있는 시간(60초)이 지났어요", 3000);
        plugin.link().done(p.getUniqueId());
        return true;
    }

    /** In the match and still playing (a player counted as left only waits for the end to be recorded). */
    boolean playing(UUID id) { MatchPlayer m = players.get(id); return m != null && !m.left; }

    /** Test join after the start (테스트 참가). */
    void rejoinAs(Player p, MatchPlayer m) {
        prepare(p, m);
        tp(p, (frozen() ? arena.starts.get(m.team)[m.slot] : arena.respawn(m.team, m.slot)).location());
        sendMatchAll(); sendShrines();
    }

    /** Admin removal (유저 퇴장): no leaver penalty, back to where they came from. */
    void kick(Player p) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null) return;
        if (quaffle != null && m.id.equals(quaffle.holder)) dropQuaffle(null, null);
        players.remove(m.id);
        p.hideBossBar(bar);
        restore(p, m);
        sendMatchAll();
        plugin.link().done(p.getUniqueId());
    }

    private void reconnectTimeouts() {
        if (now % 20 != 0) return;
        for (var m : players.values()) if (!m.online && !m.left && m.leftAt >= 0 && now - m.leftAt >= RECONNECT_TICKS) { m.left = true; feed(5, m.team.ordinal(), m.nick, "", 0); }
        // 테스트·연습은 한 팀이 비어 있는 게 정상 (혼자 테스트): 모두 나갔을 때만 끝낸다.
        if (opt.test()) {
            if (started && players.values().stream().allMatch(m -> m.left)) end(null, EndReason.ABANDONED);
            return;
        }
        if (!started) return;
        // both teams gone (e.g. the arena connection dropped for everyone): nobody wins and nothing is recorded (VOID, no leaver penalty)
        if (players.values().stream().allMatch(m -> m.left)) { end(null, EndReason.VOID); return; }
        for (Team t : Team.values()) {
            boolean any = players.values().stream().anyMatch(m -> m.team == t && !m.left);
            if (!any) { end(t.other(), EndReason.ABANDONED); return; }
        }
    }

    void ready(Player p, boolean ready) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m != null && phase == Phase.PREPARE) { m.ready = ready; sendMatchAll(); }
    }
    private boolean allReady() { return players.values().stream().filter(m -> m.online && !m.left).allMatch(m -> m.ready); }

    /** Forfeit vote (YES = give up). Returns a message for the voter. */
    String forfeit(Player p, boolean yes) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null || phase == Phase.ENDED) return "경기 중이 아니에요.";
        if (opt.test()) { end(m.team.other(), EndReason.FORFEIT); return "테스트 경기를 끝냈어요."; }
        if (elapsed < FORFEIT_MIN_TICKS) return "경기 시작 3분 뒤부터 포기할 수 있어요.";
        if (voteTeam == null) {
            if (!yes) return "진행 중인 포기 투표가 없어요.";
            if (now < voteRetryAt.getOrDefault(m.team, 0L)) return "포기 투표는 1분 뒤에 다시 할 수 있어요.";
            voteTeam = m.team; voteUntil = now + FORFEIT_WINDOW; voteYes.clear();
            for (var o : players.values()) if (o.team == m.team) { Player op = online(o); if (op != null) ChacaLink.notify(op, "system", "kyudichi:vote", "포기 투표", m.nick + " 님이 경기 포기를 제안했어요. 경기 창에서 찬성 · 반대 (15초)", 0); }
        } else if (voteTeam != m.team) return "상대 팀 투표 중이에요.";
        if (yes) voteYes.add(m.id); else voteYes.remove(m.id);
        long members = players.values().stream().filter(o -> o.team == m.team && o.online && !o.left).count();
        long need = members <= 2 ? members : members / 2 + 1;
        if (voteYes.size() >= need) { feed(5, m.team.ordinal(), "", "", 0); end(m.team.other(), EndReason.FORFEIT); return "포기했어요."; }
        sendMatchAll();
        return "포기 " + (yes ? "찬성" : "반대") + " (" + voteYes.size() + "/" + need + ")";
    }

    private void voteFailed() {
        for (var o : players.values()) if (o.team == voteTeam) { Player op = online(o); if (op != null) ChacaLink.notify(op, "system", "kyudichi:vote", "포기 투표가 부결됐어요", "", 0); }
        voteRetryAt.put(voteTeam, now + FORFEIT_RETRY);
        voteTeam = null; voteYes.clear();
        sendMatchAll();
    }

    // ================================================================== end
    void end(Team winner, EndReason reason) {
        if (phase == Phase.ENDED) return;
        if (reason != EndReason.SNITCH && phase != Phase.PREPARE) Sounds.all(this, "whistle_end", Sound.BLOCK_NOTE_BLOCK_FLUTE, 1, 1);
        phase = Phase.ENDED;
        cleanupWorld();
        plugin.results().finish(this, winner, reason);
        for (var m : players.values()) {
            Player p = Bukkit.getPlayer(m.id);
            if (p == null || !p.isOnline()) continue;
            p.hideBossBar(bar);
            if (!m.online && !m.left) continue;
            restore(p, m);
        }
        for (UUID s : List.copyOf(spectators)) { Player p = Bukkit.getPlayer(s); if (p != null) plugin.spectate().leave(p); }
        spectators.clear();
        plugin.matches().ended(this);
        plugin.link().matchEnded(this);
    }

    void restore(Player p, MatchPlayer m) {
        if (!duel) plugin.hooks().broomHangMode(p, false);
        plugin.hooks().stopBroomIfRiding(p);
        if (m.returnMode != null) p.setGameMode(m.returnMode);
        heal(p); p.setFireTicks(0); p.setFallDistance(0);
        if (m.returnTo != null && m.returnTo.getWorld() != null) tp(p, m.returnTo);
    }

    void cleanupWorld() {
        if (quaffle != null) { quaffle.visual.remove(); }
        for (Bludger b : bludgers) b.visual.remove();
        if (snitch != null) { snitch.visual.remove(); snitch = null; }
        for (var s : shrines.values()) plugin.hooks().stopVfx(s.vfx);
    }

    // ================================================================== helpers
    static Vector body(Player p) { return p.getLocation().toVector().add(new Vector(0, 0.9, 0)); }

    void tp(Player p, Location l) {
        if (l == null) return;
        ourTeleport = true;
        try { p.teleport(l); } finally { ourTeleport = false; }
    }

    private void sound(Sound s, float pitch) { for (Player p : onlinePlayers()) p.playSound(p.getLocation(), s, 1, pitch); }

    private void broadcastTitle(Component title, Component sub) {
        var t = Title.title(title, sub, Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1100), java.time.Duration.ofMillis(250)));
        for (Player p : onlinePlayers()) p.showTitle(t);
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) p.showTitle(t); }
    }

    void updateBar() {
        int left = phase == Phase.PLAY ? playLeft : phaseLeft;
        String time = String.format("%02d:%02d", left / 1200, (left / 20) % 60);
        String snitchText = duel ? " · 라운드 " + Math.max(1, round) : snitchCaught ? "" : snitch != null ? " · 스니치 등장!" : phase == Phase.PLAY ? " · 스니치 " + mmss(Math.max(0, opt.snitchAtTicks() - elapsed)) + " 후" : "";
        String head = switch (phase) { case PREPARE -> "준비 "; case COUNTDOWN -> "곧 시작 "; case GOAL_PAUSE -> duel ? "라운드 종료 " : "골! "; default -> overtime ? "골든골 " : ""; };
        bar.name(Component.text(Team.A.label + " ", Team.A.color).append(Component.text(score[0] + " : " + score[1], NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" " + Team.B.label, Team.B.color)).append(Component.text("   " + head + time + snitchText, NamedTextColor.GRAY)));
        int total = phase == Phase.PLAY ? (duel ? rules.duelRoundTicks : overtime ? rules.overtimeTicks : opt.playTicks()) : Math.max(1, phase == Phase.PREPARE ? opt.prepareTicks() : rules.countdownTicks);
        bar.progress((float) Math.clamp(left / (double) Math.max(1, total), 0, 1));
        bar.color(overtime ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
        // the boss bar is the scoreboard for players WITHOUT the client mod (the mod draws its own)
        for (Player p : onlinePlayers()) { if (plugin.net().hasMod(p)) p.hideBossBar(bar); else p.showBossBar(bar); }
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) { if (plugin.net().hasMod(p)) p.hideBossBar(bar); else p.showBossBar(bar); } }
    }

    private static String mmss(int ticks) { return String.format("%d:%02d", ticks / 1200, (ticks / 20) % 60); }

    // ------------------------------------------------------------------ client packets
    void sendMatchAll() {
        for (var m : players.values()) { Player p = online(m); if (p != null) plugin.net().send(p, matchMessage(m.team.ordinal())); }
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) plugin.net().send(p, matchMessage(2)); }
    }

    /** House, broom and rating per player (the preparation window). Sent at the start and whenever someone (re)joins. */
    void sendRoster() {
        var list = new ArrayList<KyudichiProtocol.Entry>();
        for (var m : players.values()) if (list.size() < 16) list.add(new KyudichiProtocol.Entry(m.id, m.house, m.broom == null ? "" : m.broom, (int) Math.round(m.ratingBefore)));
        var msg = new KyudichiProtocol.Roster(list);
        for (Player p : onlinePlayers()) plugin.net().send(p, msg);
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) plugin.net().send(p, msg); }
    }

    /** Arena boundary for the client's see-through limit wall (owner 2026-10-20). */
    KyudichiProtocol.Bounds boundsMessage() {
        Box b = arena.bounds;
        return b == null ? null : new KyudichiProtocol.Bounds((float) b.minX(), (float) b.minY(), (float) b.minZ(), (float) b.maxX(), (float) b.maxY(), (float) b.maxZ());
    }

    KyudichiProtocol.Match matchMessage(int myTeam) {
        int wirePhase = switch (phase) { case PREPARE -> 0; case COUNTDOWN -> 1; case PLAY -> 2; case GOAL_PAUSE -> 3; case ENDED -> 5; };
        int left = phase == Phase.PLAY ? playLeft : phaseLeft;
        int flags = (snitch != null ? 1 : 0) | (overtime ? 2 : 0) | (opt.test() ? 4 : 0) | (voteTeam != null && voteTeam.ordinal() == myTeam ? 8 : 0) | (duel ? 16 : 0);
        var list = new ArrayList<KyudichiProtocol.Person>();
        for (var m : players.values()) {
            int f = (m.dead(now) || !m.online || m.left ? 1 : 0) | (quaffle != null && m.id.equals(quaffle.holder) ? 2 : 0) | (m.ready ? 4 : 0);
            list.add(new KyudichiProtocol.Person(m.id, m.nick, m.team.ordinal(), f));
        }
        return new KyudichiProtocol.Match(wirePhase, myTeam, score[0], score[1], left * 50, flags, arena.name, list);
    }

    private void sendBalls() {
        var all = new ArrayList<KyudichiProtocol.Ball>();
        if (quaffle != null) all.add(new KyudichiProtocol.Ball(0, 0, (float) quaffle.pos.getX(), (float) quaffle.pos.getY(), (float) quaffle.pos.getZ(), quaffle.state == Quaffle.State.HELD ? 1 : 0));
        for (Bludger b : bludgers) all.add(new KyudichiProtocol.Ball(1, b.index, (float) b.pos.getX(), (float) b.pos.getY(), (float) b.pos.getZ(), 0));
        // owner 2026-10-17: no snitch marker — its position is not sent to anyone
        for (var m : players.values()) {
            Player p = online(m); if (p == null || !plugin.net().hasMod(p)) continue;
            var mine = new ArrayList<KyudichiProtocol.Ball>(all.size());
            for (var b : all) {
                boolean targeting = b.kind() == 1 && bludgers.size() > b.index() && m.id.equals(bludgers.get(b.index()).target);
                mine.add(targeting ? new KyudichiProtocol.Ball(b.kind(), b.index(), b.x(), b.y(), b.z(), b.flags() | 2) : b);
            }
            plugin.net().send(p, new KyudichiProtocol.Balls(mine));
        }
        var spec = new KyudichiProtocol.Balls(all);
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) plugin.net().send(p, spec); }
    }

    void sendShrines() {
        var list = new ArrayList<KyudichiProtocol.Shrine>();
        for (var s : shrines.values()) if (s.active()) list.add(new KyudichiProtocol.Shrine(s.type.ordinal(), (float) s.at.x(), (float) s.at.y(), (float) s.at.z()));
        var msg = new KyudichiProtocol.Shrines(list);
        var bounds = boundsMessage(); // sent along (start, rejoin, spectate, shrine changes): 25 bytes
        for (Player p : onlinePlayers()) { plugin.net().send(p, msg); if (bounds != null) plugin.net().send(p, bounds); }
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) { plugin.net().send(p, msg); if (bounds != null) plugin.net().send(p, bounds); } }
    }

    private void feed(int type, int team, String actor, String target, int points) {
        var msg = new KyudichiProtocol.Feed(type, team, actor, target, points);
        Component chat = switch (type) {
            case 1 -> Component.text("⚽ " + actor + " 골 +" + points, Team.values()[team].color);
            case 2 -> actor.isEmpty() ? Component.text("☠ " + target + " 쓰러짐", NamedTextColor.GRAY) : Component.text("⚔ " + actor + " → " + target + " 처치 +" + points, Team.values()[team].color);
            case 3 -> Component.text("✦ " + actor + " 스니치 포획 +" + points, NamedTextColor.GOLD);
            case 4 -> Component.text("골든골 연장 시작!", NamedTextColor.GOLD);
            default -> actor.isEmpty() ? Component.text(Team.values()[team].label + " 포기", NamedTextColor.GRAY) : Component.text(actor + " 님이 경기에서 나갔어요", NamedTextColor.GRAY);
        };
        for (Player p : onlinePlayers()) { if (plugin.net().hasMod(p)) plugin.net().send(p, msg); else p.sendMessage(chat); }
        for (UUID s : spectators) { Player p = Bukkit.getPlayer(s); if (p != null) { if (plugin.net().hasMod(p)) plugin.net().send(p, msg); else p.sendMessage(chat); } }
    }

    /** Relays a ping from a player to their team (and spectators). */
    void ping(Player p, KyudichiProtocol.PingRequest r) {
        MatchPlayer m = players.get(p.getUniqueId());
        if (m == null || !plugin.pings().allow(p.getUniqueId())) return;
        var msg = new KyudichiProtocol.Ping(r.kind(), m.id, m.nick, r.x(), r.y(), r.z(), r.target());
        for (var o : players.values()) if (o.team == m.team) { Player op = online(o); if (op != null) plugin.net().send(op, msg); }
        for (UUID s : spectators) { Player sp = Bukkit.getPlayer(s); if (sp != null) plugin.net().send(sp, msg); }
    }
}
