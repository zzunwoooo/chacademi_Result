package dev.portablevfx.client.drop;

import dev.portablevfx.client.PortableVfxClient;
import dev.portablevfx.client.api.PortableVfxApi;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * 등급 아이템 드롭 VFX (클라이언트 전용, 서버 패킷·서버 부담 없음).
 *
 * <p>주변 아이템 엔티티의 LORE 2번째 줄이 일반/고급/유일/전설 하나뿐이면 그 등급의 효과
 * (CommonDrop/RareDrop/UniqueDrop/LegendaryDrop 팩, static 루프)를 아이템이 바닥에 놓인 자리에 켠다.
 * 아이템을 주워 엔티티가 사라지면(또는 디스폰·합쳐짐) 그 즉시 효과를 지운다 — 원형 마법진이 남아 있지 않게.
 *
 * <ul>
 *   <li>시작: 아이템이 땅에 닿았거나 물 위에 떴거나, 떨어진 지 {@link #START_AGE_FALLBACK}틱이 지나면.</li>
 *   <li>위치: 매 틱 엔티티 위치로 {@code move} (물에 떠내려가거나 밀려도 따라감, 렌더 보간됨).</li>
 *   <li>예산: 동시에 최대 {@link #MAX_DROPS}개, PortableVFX 전체 활성이 {@link #GLOBAL_ACTIVE_LIMIT} 이상이면
 *       새로 켜지 않음(마법 VFX 자리 확보). 높은 등급·가까운 순서로 고름.</li>
 *   <li>10분(12000틱) 지속 한도 전에 새 인스턴스로 갈아타고, 새 것이 밝아진 뒤 옛 것을 지운다.</li>
 * </ul>
 */
public final class DropRarityVfx {
    static final int MAX_DROPS = 24;
    static final int GLOBAL_ACTIVE_LIMIT = 96;
    static final double RANGE = 64;
    static final int SCAN_INTERVAL = 5;
    static final int START_AGE_FALLBACK = 30;
    static final int RENEW_TICKS = 11_800;
    static final int RENEW_OVERLAP = 40;

    private static final class Live {
        final DropRarity.Grade grade;
        UUID instance, retiring;
        long started, retireAt;
        Live(DropRarity.Grade grade) { this.grade = grade; }
    }

    private final MinecraftClient client;
    private final PortableVfxApi api;
    private final Map<Integer, Live> live = new HashMap<>();
    private ClientWorld world;
    private long ticks;

    public DropRarityVfx(MinecraftClient client, PortableVfxApi api) {
        this.client = client;
        this.api = api;
    }

    /** END_CLIENT_TICK, PortableVFX runtime.tick() 다음에 호출. */
    public void tick() {
        if (client.world != world) { live.clear(); world = client.world; } // 월드 변경 때 런타임이 이미 전부 지움
        if (world == null || client.player == null) { live.clear(); return; }
        if (client.isPaused()) return;
        ticks++;
        try {
            follow();
            if (ticks % SCAN_INTERVAL == 0) scan();
        } catch (RuntimeException error) {
            PortableVfxClient.LOG.warn("Drop rarity VFX tick failed", error);
        }
    }

    /** 매 틱: 주운/사라진 아이템은 즉시 제거, 남은 것은 위치 갱신·지속 한도 갱신. */
    private void follow() {
        if (live.isEmpty()) return;
        Set<UUID> active = api.activeInstances();
        for (Iterator<Map.Entry<Integer, Live>> it = live.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Live state = entry.getValue();
            Entity entity = world.getEntityById(entry.getKey());
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || grade(item) != state.grade) {
                stop(state);
                it.remove();
                continue;
            }
            if (state.retiring != null && ticks >= state.retireAt) { api.stop(state.retiring); state.retiring = null; }
            if (state.instance == null || !active.contains(state.instance)) {
                // 거리·용량 등으로 런타임이 지웠으면 다음 스캔에서 다시 고르게 비움.
                stop(state);
                it.remove();
                continue;
            }
            Vec3d at = item.getPos();
            api.move(state.instance, at);
            if (ticks - state.started >= RENEW_TICKS && state.retiring == null) {
                var next = play(state.grade, at);
                if (next.isPresent()) {
                    state.retiring = state.instance;
                    state.retireAt = ticks + RENEW_OVERLAP;
                    state.instance = next.get();
                    state.started = ticks;
                }
            }
        }
    }

    /** 주기적으로: 등급 아이템 후보를 모아 켤 것/끌 것 결정. */
    private void scan() {
        var candidates = new ArrayList<DropRarity.Candidate>();
        var items = new HashMap<Integer, ItemEntity>();
        double rangeSq = RANGE * RANGE;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved()) continue;
            double distanceSq = item.squaredDistanceTo(client.player);
            if (distanceSq > rangeSq) continue;
            DropRarity.Grade grade = grade(item);
            if (grade == null) continue;
            if (!live.containsKey(item.getId()) && !landed(item)) continue;
            candidates.add(new DropRarity.Candidate(item.getId(), grade, distanceSq));
            items.put(item.getId(), item);
        }
        Set<Integer> chosen = DropRarity.select(candidates, live.keySet(), MAX_DROPS);
        for (Iterator<Map.Entry<Integer, Live>> it = live.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!chosen.contains(entry.getKey())) { stop(entry.getValue()); it.remove(); }
        }
        int active = api.activeInstances().size();
        for (int id : chosen) {
            if (live.containsKey(id)) continue;
            if (active >= GLOBAL_ACTIVE_LIMIT) break;
            ItemEntity item = items.get(id);
            Live state = new Live(grade(item));
            var instance = play(state.grade, item.getPos());
            if (instance.isEmpty()) continue; // 효과 미로딩·GPU 준비 전 → 다음 스캔에서 재시도
            state.instance = instance.get();
            state.started = ticks;
            live.put(id, state);
            active++;
        }
    }

    private Optional<UUID> play(DropRarity.Grade grade, Vec3d at) {
        return api.play(Identifier.of(grade.effectId), at, 1f, 0);
    }

    private void stop(Live state) {
        if (state.instance != null) api.stop(state.instance);
        if (state.retiring != null) api.stop(state.retiring);
        state.instance = state.retiring = null;
    }

    private static boolean landed(ItemEntity item) {
        return item.isOnGround() || item.isTouchingWater() || item.age >= START_AGE_FALLBACK;
    }

    static DropRarity.Grade grade(ItemEntity item) {
        var lore = item.getStack().get(DataComponentTypes.LORE);
        if (lore == null || lore.lines().size() < 2) return null;
        List<String> plain = new ArrayList<>(2);
        for (Text line : lore.lines().subList(0, 2)) plain.add(line == null ? null : line.getString());
        return DropRarity.recognize(plain);
    }

    /** /이펙트도구 상태 용 한 줄. */
    public String status() {
        return "drops=" + live.size() + "/" + MAX_DROPS;
    }
}
