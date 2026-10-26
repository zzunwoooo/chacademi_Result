package kr.chacademy.motion.broom.trail;

import kr.chacademy.motion.ChacademyMotionClient;
import kr.chacademy.motion.broom.BroomModels;
import kr.chacademy.motion.broom.BroomRenderer;
import kr.chacademy.motion.broom.BroomRiders;
import kr.chacademy.motion.broom.model.BroomModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 빗자루 비행 궤적. 나와 주변의 빗자루 탄 사람마다 PortableVFX 효과(WORLD 앵커) 하나를 켜고,
 * 매 틱 끝에 빗자루 솔 끝 위치·방향을 넘긴다. 서버 패킷 없음 (누가 어떤 빗자루인지는 broom_state 로 이미 앎).
 *
 * <h2>위치·보간</h2>
 * BroomRiders.tick(기본 단계) 다음, PortableVFX 의 틱 처리 다음({@link #LATE} 단계)에 {@code move}/{@code basis} 를 부른다.
 * PortableVFX 는 렌더 때 직전 틱 위치 → 이번 틱 위치를 tickDelta 로 보간하므로(BroomRenderer 와 같은 부분 틱),
 * 솔 끝(방출 위치)이 프레임마다 부드럽게 이동한다. world 공간 입자는 생긴 자리에 남고 방출 위치만 빗자루를 따라간다.
 * 솔 끝 = 빗자루 모델 뒤끝 중심을 BroomRenderer.draw 와 같은 회전(yaw·pitch·bank+spin)으로 옮긴 점.
 * (회전 basis 는 틱 단위로 바뀌지만 방출 위치가 솔 끝 그 자체라 방향 계단은 입자 초기 속도에만 영향.)
 *
 * <h2>종료 구분</h2>
 * <ul>
 *   <li>일반 종료(내림·낙마·범위 밖·예산): {@code finish} → onStop 꼬리(0.1~0.15초)만 보이고 사라짐.</li>
 *   <li>순간이동(한 틱에 {@link #TELEPORT_BLOCKS} 블록 이상): 옛 인스턴스 {@code stop}(즉시 제거) 후 새로 생성
 *       → 이동 거리 누적이 새로 시작되어 순간이동 경로에 선이 생기지 않음.</li>
 *   <li>월드(차원) 변경: PortableVFX 가 스스로 전부 지우므로 우리 쪽 상태만 잊는다(잔상 없음). 접속 종료도 같음.</li>
 *   <li>효과 교체(빗자루·덮어쓰기·full↔lite 변경): 옛 것 finish + 새 것 생성.</li>
 *   <li>10분(12000틱) 지속 한도 전에 {@link #RENEW_TICKS} 에서 새 인스턴스로 갈아탐.</li>
 * </ul>
 *
 * <h2>재시도</h2>
 * 생성 거절·생성 직후 사라짐 → 사람별 {@link RetryGate} 로 20→400틱까지 간격을 늘림.
 * PortableVFX 에 없는 효과 ID → 리소스 리로드 전까지 재시도 안 함(로그 한 번).
 * PortableVFX 전체 활성 수가 {@link #GLOBAL_ACTIVE_LIMIT} 이상이면 새 궤적을 만들지 않음(마법 VFX 자리 확보).
 */
public final class BroomTrails {
    public static final ResourceLocation LATE = ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "broom_trails_late");
    static final double TELEPORT_BLOCKS = 8;
    static final long RENEW_TICKS = 11_800;
    /** PortableVFX EffectRuntime.MAX_ACTIVE(128) 중 궤적이 새로 들어가지 않는 선. */
    static final int GLOBAL_ACTIVE_LIMIT = 96;
    /** 소환 연출(BroomRiders.Rider.summonScale / approachFrom) 이 끝난 뒤부터 궤적 시작. */
    static final int START_AGE = 10;

    private static final class Live {
        UUID instance;
        String effect;
        long started;
        Vec3 lastTip;
        boolean full;
    }

    private static TrailConfig config = TrailConfig.EMPTY;
    private static final TrailPlanner PLANNER = new TrailPlanner();
    private static final Map<UUID, Live> LIVE = new HashMap<>();
    private static final Map<UUID, RetryGate> GATES = new HashMap<>();
    private static final Map<UUID, String> OVERRIDES = new HashMap<>();
    /** 궤적을 잠시 숨긴 사람 → 이 틱(ticks)까지 (서버 broom_buff HIDE_TRAIL) */
    private static final Map<UUID, Long> HIDDEN = new HashMap<>();
    private static final Set<String> BAD_IDS = new HashSet<>();
    private static final Map<BroomModel, float[]> TIPS = new IdentityHashMap<>();
    private static Set<String> knownIds;
    private static long knownCheckedAt = -1_000_000;
    private static ClientLevel lastLevel;
    private static long ticks;

    private BroomTrails() {}

    public static void init() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "broom_trails");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                loadConfig(manager);
            }
        });
        // PortableVFX(기본 단계)가 직전 위치를 넘긴 뒤에 이번 틱 위치를 넣어야 렌더 보간이 맞는다
        ClientTickEvents.END_CLIENT_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
        ClientTickEvents.END_CLIENT_TICK.register(LATE, BroomTrails::tick);
    }

    private static void loadConfig(ResourceManager manager) {
        var id = ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "broom_trails.json");
        TrailConfig next = TrailConfig.EMPTY;
        var res = manager.getResource(id);
        if (res.isPresent()) {
            try (InputStream in = res.get().open()) {
                List<String> warnings = new ArrayList<>();
                next = TrailConfig.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), warnings);
                for (String w : warnings) ChacademyMotionClient.LOGGER.warn("broom_trails.json: {}", w);
            } catch (Exception ex) {
                ChacademyMotionClient.LOGGER.warn("broom_trails.json 을 읽지 못함 → 궤적 끔 ({})", ex.toString());
            }
        } else {
            ChacademyMotionClient.LOGGER.warn("broom_trails.json 없음 → 궤적 끔");
        }
        config = next;
        // 리로드 = PortableVFX 팩도 다시 읽힘. 없는 ID 판정·실패 간격을 초기화 (ID 목록은 잠시 뒤 다시 확인)
        BAD_IDS.clear();
        knownIds = null;
        knownCheckedAt = ticks; // PortableVFX 리로드가 끝나도록 20틱 뒤에 목록 확인
        GATES.values().forEach(RetryGate::reset);
        TIPS.clear();
        ChacademyMotionClient.LOGGER.info("빗자루 궤적 {}종 연결표 불러옴", config.size());
    }

    // ------------------------------------------------------------------ 아티팩트 등 덮어쓰기 (BroomApi 에서 부름)

    /** 이 플레이어의 궤적을 연결표 키로 덮어씀. 없는 키면 false. 이 PC 화면에만 적용(다른 사람 화면에는 전달 안 됨). */
    public static boolean setOverride(UUID player, String trailKey) {
        if (player == null || trailKey == null || !config.hasKey(trailKey)) return false;
        OVERRIDES.put(player, trailKey);
        return true;
    }

    public static void clearOverride(UUID player) {
        OVERRIDES.remove(player);
    }

    public static void clearOverrides() {
        OVERRIDES.clear();
    }

    /** 서버 broom_buff HIDE_TRAIL (마법 375 불꽃 추진): ticks 동안 이 사람의 궤적을 숨김 (마법 VFX 가 대신 그림). 0 = 바로 다시 보임. */
    public static void hide(UUID player, int forTicks) {
        if (player == null) return;
        if (forTicks <= 0) HIDDEN.remove(player);
        else HIDDEN.put(player, ticks + Math.min(forTicks, 20 * 60));
    }

    // ------------------------------------------------------------------ 정리

    /** 접속 종료: 덮어쓰기까지 모두 잊는다. PortableVFX 도 월드가 바뀌면 스스로 전부 지움. */
    public static void reset() {
        forgetAll(true);
        OVERRIDES.clear();
        HIDDEN.clear();
        GATES.clear();
        lastLevel = null;
    }

    private static void forgetAll(boolean stopNow) {
        if (stopNow) for (Live l : LIVE.values()) VfxBridge.stop(l.instance);
        LIVE.clear();
        PLANNER.clear();
    }

    // ------------------------------------------------------------------ 틱

    private static void tick(Minecraft mc) {
        ticks++;
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            // 월드·차원 변경: PortableVFX 가 이미 전부 지웠거나 곧 지움 → 상태만 잊음 (잔상 남기는 finish 금지)
            LIVE.clear();
            PLANNER.clear();
            lastLevel = level;
        }
        if (level == null || mc.player == null) return;
        if (BroomRiders.riders().isEmpty() && LIVE.isEmpty()) return;
        if (!VfxBridge.ready()) return;

        Set<UUID> active = LIVE.isEmpty() ? Set.of() : VfxBridge.activeInstances();
        // 우리가 끄지 않았는데 사라진 인스턴스 (렌더 쪽 거절·한도·지속 만료)
        for (Iterator<Map.Entry<UUID, Live>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!active.contains(e.getValue().instance)) {
                gate(e.getKey()).ended(ticks, ticks - e.getValue().started);
                it.remove();
            }
        }

        // 후보
        Vec3 eye = mc.player.position();
        List<TrailPlanner.Candidate> cands = new ArrayList<>();
        Map<UUID, Player> players = new HashMap<>();
        for (BroomRiders.Rider r : BroomRiders.riders().values()) {
            Player p = level.getPlayerByUUID(r.id);
            if (p == null || p.isInvisible() || r.age < START_AGE) continue;
            Long hiddenUntil = HIDDEN.get(r.id);
            if (hiddenUntil != null) {
                if (hiddenUntil > ticks) continue; // 계획에서 빠짐 → 아래에서 꼬리 남기고 종료
                HIDDEN.remove(r.id);
            }
            TrailConfig.TrailType type = config.resolve(r.broom, OVERRIDES.get(r.id));
            if (type == null) continue;
            players.put(r.id, p);
            cands.add(new TrailPlanner.Candidate(r.id, p == mc.player, p.position().distanceTo(eye), r.speed * 20.0, type));
        }
        Map<UUID, Boolean> plan = PLANNER.plan(cands, ticks);
        Map<UUID, TrailPlanner.Candidate> byId = new HashMap<>();
        for (var c : cands) byId.put(c.id(), c);

        // 계획에서 빠진 사람: 일반 종료 (꼬리 남김)
        for (Iterator<Map.Entry<UUID, Live>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!plan.containsKey(e.getKey())) {
                VfxBridge.finish(e.getValue().instance);
                it.remove();
            }
        }
        GATES.keySet().removeIf(id -> !BroomRiders.riders().containsKey(id));
        if (!HIDDEN.isEmpty()) HIDDEN.keySet().removeIf(id -> !BroomRiders.riders().containsKey(id));

        refreshKnownIds();
        int activeCount = active.size();
        for (var entry : plan.entrySet()) {
            UUID id = entry.getKey();
            boolean full = entry.getValue();
            var c = byId.get(id);
            Player p = players.get(id);
            BroomRiders.Rider r = BroomRiders.get(id);
            if (c == null || p == null || r == null) continue;
            String effect = c.type().effect(full);
            Frame f = frame(p, r);
            if (f == null) continue;
            Live live = LIVE.get(id);

            if (live != null) {
                boolean teleported = live.lastTip != null && live.lastTip.distanceToSqr(f.tip) > TELEPORT_BLOCKS * TELEPORT_BLOCKS;
                if (teleported) {
                    VfxBridge.stop(live.instance); // 즉시 제거: 순간이동 경로에 입자 안 생김
                    LIVE.remove(id);
                    live = null;
                } else if (!live.effect.equals(effect) || ticks - live.started >= RENEW_TICKS) {
                    VfxBridge.finish(live.instance); // 교체: 옛 꼬리는 자연스럽게 사라짐
                    LIVE.remove(id);
                    live = null;
                } else {
                    VfxBridge.move(live.instance, f.tip);
                    VfxBridge.basis(live.instance, f.right, f.up, f.forward);
                    live.lastTip = f.tip;
                    continue;
                }
            }
            // 새로 생성
            RetryGate g = gate(id);
            if (!g.allowed(ticks) || BAD_IDS.contains(effect)) continue;
            if (knownIds == null || knownIds.isEmpty()) {
                g.failed(ticks); // PortableVFX 가 아직 팩을 안 불러옴: 간격 두고 다시
                continue;
            }
            if (!knownIds.contains(effect)) {
                BAD_IDS.add(effect);
                ChacademyMotionClient.LOGGER.warn("빗자루 궤적 효과 {} 가 PortableVFX 에 없음 (리소스 리로드 전까지 재시도 안 함)", effect);
                continue;
            }
            if (activeCount >= GLOBAL_ACTIVE_LIMIT) {
                g.failed(ticks); // 마법 VFX 자리 확보: 간격 두고 다시
                continue;
            }
            UUID inst = VfxBridge.play(ResourceLocation.parse(effect), f.tip);
            if (inst == null) {
                g.failed(ticks);
                continue;
            }
            activeCount++;
            VfxBridge.basis(inst, f.right, f.up, f.forward);
            Live l = new Live();
            l.instance = inst;
            l.effect = effect;
            l.started = ticks;
            l.lastTip = f.tip;
            l.full = full;
            LIVE.put(id, l);
        }
    }

    private static RetryGate gate(UUID id) {
        return GATES.computeIfAbsent(id, k -> new RetryGate());
    }

    /** PortableVFX 효과 목록은 리로드 직후·비어 있을 때만 다시 받는다 (매 틱 복사하지 않음). */
    private static void refreshKnownIds() {
        boolean stale = knownIds == null || (knownIds.isEmpty() && ticks - knownCheckedAt >= 100);
        if (!stale || ticks - knownCheckedAt < 20) return;
        knownCheckedAt = ticks;
        knownIds = VfxBridge.loadedIds();
    }

    // ------------------------------------------------------------------ 빗자루 솔 끝

    record Frame(Vec3 tip, Vec3 right, Vec3 up, Vec3 forward) {}

    /** BroomRenderer.draw 와 같은 변환: 좌석 → YP(180-yaw) · XP(-pitch) · ZP(bank+spin) · (1/16) · (-seat). 이번 틱 값(보간은 PortableVFX 가). */
    private static Frame frame(Player p, BroomRiders.Rider r) {
        BroomModel model = BroomModels.get(r.broom);
        if (model == null) return null;
        float[] tip = TIPS.computeIfAbsent(model, BroomTrails::tipOf);
        Quaternionf q = new Quaternionf()
                .rotateY((float) Math.toRadians(180f - r.yaw))
                .rotateX((float) Math.toRadians(-r.pitch))
                .rotateZ((float) Math.toRadians(r.bank + r.spin));
        Vector3f local = new Vector3f((tip[0] - model.seat[0]) / 16f, (tip[1] - model.seat[1]) / 16f, (tip[2] - model.seat[2]) / 16f);
        q.transform(local);
        Vec3 feet = p.position();
        Vec3 tipW = new Vec3(feet.x + local.x, feet.y + BroomRenderer.SEAT_Y + local.y, feet.z + local.z);
        Vector3f fwd = q.transform(new Vector3f(0, 0, -1)); // 모델 앞(-Z) = 빗자루 코
        Vector3f up = q.transform(new Vector3f(0, 1, 0));
        Vector3f right = new Vector3f(up).cross(fwd);       // PortableVFX ClaudeFrames: right = up × forward
        if (!finite(tipW) || right.lengthSquared() < 1e-6f) return null;
        right.normalize();
        up.normalize();
        fwd.normalize();
        return new Frame(tipW, vec(right), vec(up), vec(fwd));
    }

    /** 솔 끝 판정 띠 두께 (픽셀). 뒤로 비스듬히 뻗은 솔 장식(broom_2 등) 한쪽만 보지 않도록 띠 전체의 중심을 쓴다. */
    static final float TIP_BAND_PX = 4f;

    /** 솔 끝 (픽셀): x,y = 뒤끝(+Z 최대)에서 4px 안의 모든 꼭짓점 중심, z = 뒤끝 - 1px. */
    static float[] tipOf(BroomModel model) {
        float maxZ = model.bounds[5];
        double sx = 0, sy = 0;
        int n = 0;
        for (var q : model.quads) {
            float[] pos = q.pos();
            for (int i = 0; i < 4; i++) {
                if (pos[i * 3 + 2] >= maxZ - TIP_BAND_PX) {
                    sx += pos[i * 3];
                    sy += pos[i * 3 + 1];
                    n++;
                }
            }
        }
        if (n == 0) return new float[]{model.seat[0], model.seat[1], maxZ};
        return new float[]{(float) (sx / n), (float) (sy / n), maxZ - 1f};
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x, v.y, v.z);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** 디버그: 지금 켜진 궤적 수. */
    public static int liveCount() {
        return LIVE.size();
    }
}
