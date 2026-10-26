package kr.chacademy.motionplugin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademy.motionplugin.broom.BroomCommand;
import kr.chacademy.motionplugin.broom.BroomService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 모드 → chacademy:cast → (검증) → 주변 유저 → chacademy:anim
 */
public final class ChacademyMotionPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    private static ChacademyMotionPlugin instance;

    private final Map<UUID, Long> lastCast = new HashMap<>();
    private Set<String> allowed = Set.of();
    private long cooldownMs;
    private boolean rangeMode;
    private double rangeSq;
    private boolean debug;
    private BroomService brooms;

    public static ChacademyMotionPlugin get() {
        return instance;
    }

    // ------------------------------------------------------------------ 빗자루 비행 API
    // 다른 플러그인(마법 미몹 등)은 컴파일 의존 없이 리플렉션으로 아래 메서드를 부른다.

    /**
     * 비행 마법 진입점. 타고 있으면 내리고, 아니면 탄다
     * (빗자루가 있으면 시간 제한 없음, 없으면 연습용 빗자루로 practiceTicks 동안).
     *
     * @return 처리했으면 true, 모드가 없어서 빗자루 비행을 못 하면 false (호출한 쪽에서 예전 방식으로 처리)
     */
    public boolean broomFlight(@NotNull Player player, int practiceTicks) {
        return brooms != null && brooms.flightSpell(player, practiceTicks);
    }

    /** 빗자루를 타고 있는지. 위치를 바꾸는 마법을 막을 때 쓴다. */
    public boolean isRidingBroom(@NotNull Player player) {
        return brooms != null && brooms.isRiding(player);
    }

    /**
     * 빗자루 비행 속도 {x, y, z} (블록/틱). 타고 있지 않으면 null.
     * 투사체 마법(마법 미몹 chabolt, PortableVFX 투사체 연출)이 리플렉션으로 불러
     * "탄 사람 속도만큼 더 빠르게, 지연만큼 앞에서" 출발시킨다.
     */
    public double[] broomVelocity(@NotNull Player player) {
        return brooms == null ? null : brooms.velocity(player);
    }

    /** 강제로 빗자루에서 내리게 한다 (구역 보호 등). */
    public void stopBroom(@NotNull Player player) {
        if (brooms != null) brooms.stop(player);
    }

    /** 균형(안정성) 게이지를 가득 채운다 (큐디치 안정성 성소). 타고 있지 않으면 false. */
    /** 장착한 빗자루 id (없으면 ""). 큐디치 준비 화면에 쓴다. */
    public String equippedBroomId(@NotNull Player player) {
        var t = brooms == null ? null : brooms.ownedBroom(player);
        return t == null ? "" : t.id();
    }

    public boolean refillBroomBalance(@NotNull Player player) {
        return brooms != null && brooms.fx(player, kr.chacademy.motionplugin.broom.BroomCodec.FX_REFILL_BALANCE);
    }

    /** 기본 가속과 같은 가속을 바로 한 번. 가속 쿨타임은 쓰지도 시작하지도 않아 쿨타임 중에도 된다 (큐디치 가속 성소). */
    public boolean freeBroomBoost(@NotNull Player player) {
        return brooms != null && brooms.fx(player, kr.chacademy.motionplugin.broom.BroomCodec.FX_FREE_BOOST);
    }

    /**
     * 큐디치(오너 2026-10-20): 낙마 대신 매달림. 켜 두면 균형 0 → 그 자리에서 한 팔로 매달려 회복 후 다시 올라탐.
     * 경기장 플러그인이 리플렉션으로 부른다. 모드가 없으면 false.
     */
    public boolean setBroomHangMode(@NotNull Player player, boolean on) {
        return brooms != null && brooms.hangMode(player, on);
    }

    /**
     * 아티팩트 빗자루 효과 (2026-10-26, BroomCodec.PERK_* 비트 묶음: 1 빗자루 끈, 2 빗자루 쿠션, 4 바람개비 장식,
     * 8 별의 잔향, 16 폭풍 날개). MagicCodexBridge 가 장착이 바뀔 때 리플렉션으로 부른다. 0 = 없음.
     */
    public void setBroomPerks(@NotNull Player player, int perks) {
        if (brooms != null) brooms.setPerks(player, perks);
    }

    /**
     * 시간제 최고 속도 버프 (마법 375 불꽃 추진, 2026-10-25): ticks 동안 최고 속도 × mult.
     * hideTrail 이면 같은 시간 동안 본인·주변 화면에서 빗자루 궤적을 숨긴다 (마법 VFX 가 불꽃 궤적을 그림).
     * 타고 있지 않거나 모드가 오래돼 채널이 없으면 false.
     */
    public boolean broomSpeedBuff(@NotNull Player player, float mult, int ticks, boolean hideTrail) {
        if (brooms == null || !brooms.buff(player, kr.chacademy.motionplugin.broom.BroomCodec.BUFF_SPEED, ticks, mult)) return false;
        if (hideTrail) brooms.buff(player, kr.chacademy.motionplugin.broom.BroomCodec.BUFF_HIDE_TRAIL, ticks, 0);
        return true;
    }

    /**
     * 시간제 균형 버프 (마법 380 순풍, 2026-10-25): refill 이면 균형 게이지를 바로 가득, ticks 동안 균형 감소 × drainMult.
     * 타고 있지 않거나 모드가 오래돼 채널이 없으면 false.
     */
    public boolean broomBalanceBuff(@NotNull Player player, float drainMult, int ticks, boolean refill) {
        if (brooms == null || !brooms.buff(player, kr.chacademy.motionplugin.broom.BroomCodec.BUFF_BALANCE_DRAIN, ticks, drainMult)) return false;
        if (refill) brooms.fx(player, kr.chacademy.motionplugin.broom.BroomCodec.FX_REFILL_BALANCE);
        return true;
    }

    public BroomService brooms() {
        return brooms;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadSettings();

        var messenger = getServer().getMessenger();
        messenger.registerIncomingPluginChannel(this, MotionCodec.CHANNEL_CAST, this);
        messenger.registerOutgoingPluginChannel(this, MotionCodec.CHANNEL_ANIM);

        getServer().getPluginManager().registerEvents(this, this);

        brooms = new BroomService(this);
        brooms.start();
        registerCommands();
        getLogger().info("모션 채널 등록 완료 (" + MotionCodec.CHANNEL_CAST + " → " + MotionCodec.CHANNEL_ANIM + ")");
    }

    @Override
    public void onDisable() {
        if (brooms != null) brooms.shutdown();
        var messenger = getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(this);
        messenger.unregisterOutgoingPluginChannel(this);
        instance = null;
    }

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();
        allowed = new HashSet<>(cfg.getStringList("allowed-animations"));
        cooldownMs = Math.max(0, cfg.getLong("cooldown-ms", 500));
        rangeMode = "range".equalsIgnoreCase(cfg.getString("broadcast-mode", "tracked"));
        double range = cfg.getDouble("range", 48);
        rangeSq = range * range;
        debug = cfg.getBoolean("debug", false);
    }

    // ------------------------------------------------------------------ 모드 → 서버

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!MotionCodec.CHANNEL_CAST.equals(channel)) return;

        String animation;
        try {
            animation = MotionCodec.decodeCast(message);
        } catch (MotionCodec.MalformedPacketException e) {
            if (debug) getLogger().warning(player.getName() + " 의 잘못된 cast 패킷: " + e.getMessage());
            return;
        }

        if (!allowed.contains(animation)) {
            if (debug) getLogger().info(player.getName() + " 이(가) 허용되지 않은 모션 요청: " + animation);
            return;
        }
        if (!player.hasPermission("chacademy.motion.cast")) return;

        long now = System.currentTimeMillis();
        Long last = lastCast.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        lastCast.put(player.getUniqueId(), now);

        var event = new MotionCastEvent(player, animation, true);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        // 시전자는 이미 자기 화면에서 재생했으므로 제외
        int sent = broadcast(player, animation, false);
        if (debug) getLogger().info(player.getName() + " → " + animation + " (" + sent + "명에게 전송)");
    }

    // ------------------------------------------------------------------ 서버 → 모드

    /**
     * 서버 쪽에서 모션을 재생시킨다. 다른 플러그인(마법 판정 등)에서 호출하면 된다.
     *
     * @param includeSelf 시전자 본인 화면에서도 재생할지 (서버 주도 방식이면 true)
     * @return 패킷을 보낸 유저 수 (모드를 안 깐 유저는 Bukkit 이 알아서 건너뜀)
     */
    public int playMotion(@NotNull Player player, @NotNull String animation, boolean includeSelf) {
        var event = new MotionCastEvent(player, animation, false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;
        return broadcast(player, animation, includeSelf);
    }

    private int broadcast(Player caster, String animation, boolean includeSelf) {
        byte[] payload = MotionCodec.encodeAnim(caster.getUniqueId(), animation);
        int count = 0;
        for (Player viewer : viewersOf(caster)) {
            if (viewer.equals(caster)) continue;
            viewer.sendPluginMessage(this, MotionCodec.CHANNEL_ANIM, payload);
            count++;
        }
        if (includeSelf) {
            caster.sendPluginMessage(this, MotionCodec.CHANNEL_ANIM, payload);
            count++;
        }
        return count;
    }

    private Collection<? extends Player> viewersOf(Player caster) {
        if (!rangeMode) {
            // Paper API: 이 플레이어를 클라이언트에 불러와 둔(=볼 수 있는) 유저들
            return caster.getTrackedBy();
        }
        List<Player> list = new ArrayList<>();
        var loc = caster.getLocation();
        for (Player p : caster.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= rangeSq) list.add(p);
        }
        return list;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastCast.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ 명령어 (MagicCodexBridge 명령어 허브)

    private void registerCommands() {
        if (!ChacaLink.ready()) {
            getLogger().warning("MagicCodexBridge 명령어 허브가 없어 빗자루·모션 명령을 등록하지 않았습니다 (빗자루 비행·모션 전달은 그대로 동작).");
            return;
        }
        try {
            new BroomCommand(this, brooms).register();
            ChacaLink.branch(this, ChacaLink.테스트, motionTest(), "서버에서 모션 재생 (본인 화면 포함)", "모션 ");
            ChacaLink.reload(this, "빗자루", "brooms.yml (빗자루 목록·수치)", () -> brooms.reload());
            ChacaLink.reload(this, "모션", "config.yml (허용 모션·쿨타임·전달 범위)", this::loadSettings);
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.WARNING, "명령어 허브에 빗자루·모션 명령을 등록하지 못했습니다", e);
        }
    }

    /** /테스트 모션 &lt;모션&gt; [닉네임] */
    private LiteralArgumentBuilder<CommandSourceStack> motionTest() {
        return Commands.literal("모션")
                .then(Commands.argument("모션", ChacaLink.word(() -> new ArrayList<>(allowed)))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player p)) throw ChacaLink.error("닉네임을 적어 주세요");
                            playTest(ctx.getSource().getSender(), p, ctx.getArgument("모션", String.class));
                            return 1;
                        })
                        .then(Commands.argument(ChacaLink.TARGET, ChacaLink.player())
                                .executes(ctx -> {
                                    playTest(ctx.getSource().getSender(), ChacaLink.resolveOnline(ctx, ChacaLink.TARGET), ctx.getArgument("모션", String.class));
                                    return 1;
                                })));
    }

    private void playTest(CommandSender sender, Player target, String animation) {
        int sent = playMotion(target, animation, true);
        ChacaLink.ok(sender, ChacaLink.name(target.getUniqueId()) + " 님의 " + animation + " 모션을 재생했어요 (" + sent + "명에게 전송)");
    }
}
