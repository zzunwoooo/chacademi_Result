package school.magiccodex.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/**
 * 생활 아티팩트 (2026-10-26, 보류 29종 중 그룹 B): 반응 일지 · 보온 도시락 · 삑삑이 신발 · 냠냠 턱받이.
 * 접이식 망원경은 클라 모드(magic-codex-fabric SpyglassZoom)가 장착 목록을 보고 직접 확대한다.
 *
 * 전부 바닐라 API, 2틱 검사({@link #fast})는 장착한 사람만 · 숫자 몇 개 비교뿐이라 서버 부담 없음.
 *  - 보온 도시락: 서버가 먹기 시작한 아이템의 남은 사용 시간(setActiveItemRemainingTime)을 1/1.2 로 줄인다.
 *    아이템(consumable 컴포넌트)은 건드리지 않는다. 먹기가 끝나는 판정은 서버라 클라 애니메이션은 조금 일찍 끝난다.
 *  - 삑삑이 신발 · 냠냠 턱받이: 바닐라 발소리·먹는 소리는 클라가 직접 내므로 서버만으로는 지울 수 없다.
 *    대신 걸음(약 1.7블록)마다 / 먹는 동안 귀여운 소리를 world.playSound 로 덧붙인다 (주변 사람도 들림).
 */
final class ArtifactComfort implements Listener {
    /** 반응 일지: 새 마법 발견 1번 = 경험치 50 (레벨 10 전후 기준 약 1~2레벨, 여행자의 수첩 바이옴 30보다 조금 큼). */
    static final int JOURNAL_EXP = 50;
    /** 보온 도시락: 먹는 속도 +20% → 사용 시간 ÷1.2 */
    static final double LUNCHBOX_SPEED = 1.2;
    /** 삑삑이 신발: 이만큼 걸을 때마다 삑 (바닐라 발소리 간격과 비슷) */
    static final double STRIDE = 1.7;

    private final ArtifactEffects fx;
    /** 보온 도시락: 이번 먹기에서 이미 줄였는지 */
    private final Set<UUID> hastened = new HashSet<>();
    /** 삑삑이 신발: 마지막 위치 {x, z} 와 쌓인 걸음 거리, 왼발/오른발 */
    private final Map<UUID, double[]> steps = new HashMap<>();
    /** 냠냠 턱받이: 먹는 동안 2틱 검사 횟수 */
    private final Map<UUID, Integer> chews = new HashMap<>();

    ArtifactComfort(ArtifactEffects fx) { this.fx = fx; }

    private boolean has(Player p, String id) { return fx.has(p, id); }

    /** 먹는 중인 음식 (물약·우유 등 마시는 것 제외). */
    private static boolean eating(Player p) {
        return p.hasActiveItem() && p.getActiveItem().getType().isEdible();
    }

    // ------------------------------------------------------------------ 2틱마다 (ArtifactEffects.fastTick, 장착한 사람만)

    void fast(Player p) {
        UUID id = p.getUniqueId();
        boolean eating = eating(p);
        if (has(p, "thermos_lunchbox") && eating) {
            // 먹기 시작 뒤 첫 검사(2틱 안)에서 한 번만: 남은 시간 = 남은 시간 ÷ 1.2
            if (hastened.add(id)) {
                int left = p.getActiveItemRemainingTime();
                int next = Math.max(1, (int) Math.round(left / LUNCHBOX_SPEED));
                if (next < left) p.setActiveItemRemainingTime(next);
            }
        } else hastened.remove(id);

        if (has(p, "yum_bib") && eating) {
            int n = chews.merge(id, 1, Integer::sum);
            Location at = p.getLocation();
            if (n % 4 == 1) at.getWorld().playSound(at, Sound.ENTITY_PANDA_EAT, SoundCategory.PLAYERS, .55f, 1.8f);
            else if (n % 4 == 3) at.getWorld().playSound(at, Sound.ENTITY_FOX_EAT, SoundCategory.PLAYERS, .5f, 1.6f);
        } else chews.remove(id);

        if (has(p, "squeaky_shoes")) squeak(p); else steps.remove(id);
    }

    private void squeak(Player p) {
        Location at = p.getLocation();
        double[] s = steps.get(p.getUniqueId());
        if (s == null) { steps.put(p.getUniqueId(), new double[]{at.getX(), at.getZ(), 0, 0}); return; }
        double dx = at.getX() - s[0], dz = at.getZ() - s[1];
        s[0] = at.getX(); s[1] = at.getZ();
        if (!p.isOnGround() || p.isFlying() || p.isGliding() || p.isSwimming() || p.isInsideVehicle()) return;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 2) return; // 순간이동·서버 이동
        s[2] += d;
        if (s[2] < STRIDE) return;
        s[2] = 0;
        s[3] = 1 - s[3];
        // 몰래 다니기는 글렀다: 웅크려도 삑 (조금 작게)
        float volume = p.isSneaking() ? .25f : .45f;
        at.getWorld().playSound(at, Sound.ENTITY_BAT_AMBIENT, SoundCategory.PLAYERS, volume, s[3] == 0 ? 1.85f : 2f);
        at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_FLUTE, SoundCategory.PLAYERS, volume * .5f, s[3] == 0 ? 1.9f : 2f);
    }

    /** 냠냠 턱받이: 다 먹으면 작은 트림 + 말풍선. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void ate(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!has(p, "yum_bib") || !e.getItem().getType().isEdible()) return;
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, .5f, 1.7f);
        if (fx.ready(p, "yum_bib", 4_000)) fx.bubble(p, "냠냠!", 30);
    }

    // ------------------------------------------------------------------ 반응 일지 (MagicDiscovery → MagicCodexBridge.discoveryFirst)

    void discovered(UUID player, String spell) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline() || !has(p, "discovery_journal")) return;
        p.giveExp(JOURNAL_EXP);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, .8f, 1.3f);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .6f, 1.2f);
        ArtifactEffects.notice(p, "discovery_journal", "반응 일지", "새 마법을 적어 두었어요 (경험치 +" + JOURNAL_EXP + ")");
    }

    void quit(UUID id) { hastened.remove(id); steps.remove(id); chews.remove(id); }
}
