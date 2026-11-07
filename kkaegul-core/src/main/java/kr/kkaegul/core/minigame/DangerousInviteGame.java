package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 위험한 초대: 의자(invite-chair.N)에 앉히고 제한시간 안에 WASD 랜덤 커맨드 입력.
 * 성공: 우편함으로 고급상자 / 실패: 의자가 뒤로 젖혀지며 뒤쪽(수영장)으로 날아감 + 빅증가.
 *
 * 의자 그림: 클라이언트 모드에 든 모델(kkaegul:flying_chair_base / _seat)을 ItemDisplay 2개로 띄운다.
 * (원본 위험한초대 서버의 ModelEngine flying_chair 모델을 옮긴 것. ModelEngine 없이 동작)
 * 의자 방향 = invite-chair.N 위치의 바라보는 방향(무대 쪽). 실패하면 그 반대(등 뒤)로 날아간다.
 */
public final class DangerousInviteGame extends Minigame {
    private final List<Entity> seats = new ArrayList<>();
    public final Set<UUID> seated = new HashSet<>();
    private int finished = 0;

    /** 의자 그림 (위치 순서대로) */
    private static final class Chair { Location loc; ItemDisplay base, seat; Entity me; }
    private final List<Chair> chairs = new ArrayList<>();
    private final java.util.Map<UUID, Integer> chairOf = new java.util.HashMap<>();
    private static final String TAG = "kkaegul_chair";
    // 등받이 회전축 (원본 모델 pivot -2, 9, 5 → 디스플레이 기준 좌표, 아이템 디스플레이는 모델을 180도 돌려 그림)
    private static final Vector3f PIVOT = new Vector3f(2f / 16f, (9f - 8f) / 16f, -5f / 16f);

    public DangerousInviteGame(KkaegulCore plugin) {
        super(plugin, "invite");
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::maintainChairs, 60L, 100L);
    }

    // ───────── 의자 그림 ─────────
    private boolean chairModel() { return plugin.getConfig().getBoolean("minigame.games.invite.chair-model", true); }

    private static ItemStack modelItem(String id) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.setItemModel(NamespacedKey.fromString("kkaegul:" + id)); it.setItemMeta(m); }
        return it;
    }

    private ItemDisplay display(Location at, String model) {
        Entity e = at.getWorld().spawnEntity(at, EntityType.ITEM_DISPLAY);
        ItemDisplay d = (ItemDisplay) e;
        d.setItemStack(modelItem(model));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setPersistent(false);   // 청크가 내려가면 사라지고, 다시 올라오면 maintainChairs 가 새로 띄움 (중복 없음)
        d.setInvulnerable(true);
        d.setViewRange(1.5f);
        d.setShadowRadius(.45f);
        d.addScoreboardTag(TAG);
        return d;
    }

    /** 5초마다: 위치가 바뀌었거나 사라진 의자 다시 띄우기 */
    private void maintainChairs() {
        if (!chairModel()) { clearChairs(); return; }
        List<Location> locs = plugin.places().list("invite-chair");
        boolean changed = locs.size() != chairs.size();
        for (int i = 0; !changed && i < locs.size(); i++) changed = !locs.get(i).equals(chairs.get(i).loc);
        if (changed) {
            clearChairs();
            for (Location l : locs) { Chair c = new Chair(); c.loc = l.clone(); chairs.add(c); }
        }
        String me = plugin.getConfig().getString("minigame.games.invite.chair-me-model", "");
        boolean useMe = !me.isBlank() && kr.kkaegul.core.casino.ModelEngineHook.present();
        for (Chair c : chairs) {
            if (useMe) {   // ModelEngine 원본 모델 (flying_chair.bbmodel) 사용
                if (c.me != null && c.me.isValid()) continue;
                if (c.loc.getWorld() == null || !c.loc.getWorld().isChunkLoaded(c.loc.getBlockX() >> 4, c.loc.getBlockZ() >> 4)) continue;
                Location at = c.loc.clone();
                at.setPitch(0);
                Entity st = at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
                if (st instanceof ArmorStand as) { as.setInvisible(true); as.setGravity(false); as.setMarker(true); as.setInvulnerable(true); as.setPersistent(false); as.addScoreboardTag(TAG); }
                kr.kkaegul.core.casino.ModelEngineHook.apply(st, me);
                c.me = st;
                continue;
            }
            if (c.base != null && c.base.isValid()) continue;
            if (c.loc.getWorld() == null || !c.loc.getWorld().isChunkLoaded(c.loc.getBlockX() >> 4, c.loc.getBlockZ() >> 4)) continue;
            if (c.seat != null && c.seat.isValid()) c.seat.remove();
            Location at = c.loc.clone().add(0, 0.5, 0);
            at.setPitch(0);
            c.base = display(at, "flying_chair_base");
            c.seat = display(at, "flying_chair_seat");
        }
    }

    private void clearChairs() {
        for (Chair c : chairs) {
            if (c.base != null && c.base.isValid()) c.base.remove();
            if (c.seat != null && c.seat.isValid()) c.seat.remove();
            if (c.me != null && c.me.isValid()) c.me.remove();
        }
        chairs.clear();
    }

    /** 등받이 젖히기(flip=true) / 되돌리기 */
    private void tilt(int idx, boolean flip, int ticks) {
        if (idx < 0 || idx >= chairs.size()) return;
        Chair ch = chairs.get(idx);
        if (ch.me != null) {   // ModelEngine: 젖히기 = 원본 애니메이션, 되돌리기 = 모델 새로 띄우기
            if (flip) kr.kkaegul.core.casino.ModelEngineHook.play(ch.me, plugin.getConfig().getString("minigame.games.invite.chair-me-animation", "animation"));
            else if (ticks > 0 || ch.me.isValid()) { ch.me.remove(); ch.me = null; maintainChairs(); }
            return;
        }
        ItemDisplay seat = ch.seat;
        if (seat == null || !seat.isValid()) return;
        Quaternionf q = new Quaternionf();
        if (flip) q.rotateX((float) Math.toRadians(-90));
        Vector3f rp = q.transform(new Vector3f(PIVOT.x, PIVOT.y, PIVOT.z));
        Vector3f t = new Vector3f(PIVOT.x, PIVOT.y, PIVOT.z).sub(rp);
        seat.setInterpolationDelay(0);
        seat.setInterpolationDuration(ticks);
        seat.setTransformation(new Transformation(t, q, new Vector3f(1, 1, 1), new Quaternionf()));
    }

    @Override public String label() { return "위험한 초대"; }

    /** 의자 수만큼만 초대 (의자 4개면 4명) */
    @Override public int capacity() { int n = plugin.places().list("invite-chair").size(); return n <= 0 ? Integer.MAX_VALUE : n; }

    @Override public String readiness() { return plugin.places().list("invite-chair").isEmpty() ? "위치 invite-chair.1 이상 필요" : null; }

    @Override
    protected void start(List<Player> list) {
        List<Location> locs = plugin.places().list("invite-chair");
        maintainChairs();
        for (int c = 0; c < chairs.size(); c++) tilt(c, false, 0);
        finished = 0;
        chairOf.clear();
        int i = 0;
        for (Player p : list) {
            int idx = i % locs.size();
            Location chair = locs.get(idx).clone();
            i++;
            chairOf.put(p.getUniqueId(), idx);
            if (cfgBool("naked", true)) stash(p);   // 맨몸으로 (아이템은 맡겨 두고 끝나면 돌려줌)
            p.teleport(chair);
            Entity seat = chair.getWorld().spawnEntity(chair.clone().add(0, cfgDouble("seat-height", 0.5), 0), EntityType.ARMOR_STAND);
            if (seat instanceof ArmorStand as) {
                as.setInvisible(true);
                as.setGravity(false);
                as.setMarker(true);
                as.setInvulnerable(true);
                as.setPersistent(false);
            }
            seat.addPassenger(p);
            seats.add(seat);
            seated.add(p.getUniqueId());
            plugin.ui().fx(p, "invite_letter", Json.obj().put("title", "깨굴이의 위험한 초대").put("to", plugin.displayName(p)).put("place", "위험한 의자"));
        }
        int len = cfgInt("length", 7), sec = cfgInt("seconds", 7);
        waitPhase(cfgInt("wait-seconds", 30), "위험한 초대 시작", () -> {
            for (Player p : online()) hudOpen(p, Json.obj());
            later(cfgInt("countdown-ticks", 60), () -> {
                for (Player p : online()) plugin.wasd().start(p, len, sec, cfgInt("per-key-seconds", 3), label(), this::success, this::fail);
            });
            int pk = cfgInt("per-key-seconds", 3), total = pk > 0 ? pk * len : sec;
            later(cfgInt("countdown-ticks", 60) + (total + 8) * 20L, this::end);
        });
    }

    private void success(Player p) {
        seated.remove(p.getUniqueId());
        rewardSuccess(p);
        setState(p.getUniqueId(), "success");
        resultFx(p, true, "살아남았다! 우편함에 고급상자 선물", "고급상자");
        done();
    }

    private void fail(Player p) {
        seated.remove(p.getUniqueId());
        Integer idx = chairOf.get(p.getUniqueId());
        if (idx != null) tilt(idx, true, 3);   // 등받이가 뒤로 휙
        later(3, () -> {
            if (!p.isOnline()) return;
            Entity v = p.getVehicle();
            if (v != null) v.eject();
            Location chair = idx != null && idx < plugin.places().list("invite-chair").size() ? plugin.places().list("invite-chair").get(idx) : null;
            Vector back;
            if (chair != null) {
                Location f = chair.clone();
                f.setPitch(0);
                back = f.getDirection().multiply(-1).normalize().multiply(cfgDouble("launch-back", 1.4)).setY(cfgDouble("launch-up", 0.7));
            } else back = new Vector(Rng.r().nextDouble() * 2 - 1, cfgDouble("launch-power", 2.2), Rng.r().nextDouble() * 2 - 1);
            // 내리는 틱에 서버가 위치를 다시 잡으면서 속도가 지워짐 → 내린 다음 틱부터 몇 번 다시 밀어 줌
            for (int t = 1; t <= 3; t++) later(t, () -> { if (p.isOnline() && !p.isDead() && p.getVehicle() == null) p.setVelocity(back); });
        });
        if (idx != null) later(30, () -> tilt(idx, false, 6));
        // 멀리 날아간 뒤 사망 (아이템 유지)
        int killAfter = cfgInt("kill-after-ticks", 40);
        if (killAfter > 0) later(3 + killAfter, () -> {
            if (!p.isOnline() || p.isDead()) return;
            plugin.harass().keepInv.add(p.getUniqueId());
            p.setHealth(0);
        });
        rewardFail(p);
        setState(p.getUniqueId(), "out");
        later(20, () -> { if (p.isOnline()) resultFx(p, false, "의자가 뒤로 휙! 빅증가 룰렛", "빅증가 룰렛"); });
        Location drop = plugin.places().get("invite-drop");
        if (drop != null) later(cfgInt("drop-delay-ticks", 40), () -> { if (p.isOnline()) p.teleport(drop); });
        done();
    }

    private void done() {
        finished++;
        if (finished >= players.size()) later(80, this::end);
    }

    // ───────── 맨몸 입장: 인벤토리 맡기기 / 돌려주기 ─────────
    private void stash(Player p) {
        kr.kkaegul.core.data.PlayerData d = plugin.data().get(p);
        if (!d.stash.isEmpty()) return;   // 이미 맡긴 게 있으면 그대로 (중복 방지)
        org.bukkit.inventory.ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++)
            if (c[i] != null && c[i].getType() != org.bukkit.Material.AIR) d.stash.add(i + "|" + kr.kkaegul.core.item.MailService.encode(c[i]));
        d.stashBack = kr.kkaegul.core.util.Locs.toStr(p.getLocation());
        p.getInventory().clear();
        d.markDirty();
        plugin.data().saveNow(d);
    }

    /** 맡긴 인벤토리 돌려주기 (게임 끝·부활·접속 때). 빈 칸에 넣고 넘치면 우편함 */
    public static void unstash(KkaegulCore plugin, Player p) {
        kr.kkaegul.core.data.PlayerData d = plugin.data().get(p);
        if (d.stash.isEmpty()) return;
        java.util.List<String> items = new ArrayList<>(d.stash);
        d.stash.clear();
        d.stashBack = "";
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (String s : items) {
            int bar = s.indexOf('|');
            if (bar <= 0) continue;
            org.bukkit.inventory.ItemStack it;
            try { it = kr.kkaegul.core.item.MailService.decode(s.substring(bar + 1)); } catch (RuntimeException e) { continue; }
            if (it == null) continue;
            int slot;
            try { slot = Integer.parseInt(s.substring(0, bar)); } catch (NumberFormatException e) { slot = -1; }
            org.bukkit.inventory.ItemStack cur = slot >= 0 && slot < inv.getSize() ? inv.getItem(slot) : null;
            if (slot >= 0 && slot < inv.getSize() && (cur == null || cur.getType() == org.bukkit.Material.AIR)) inv.setItem(slot, it);
            else for (org.bukkit.inventory.ItemStack left : inv.addItem(it).values()) plugin.mail().send(p.getUniqueId(), left, "위험한 초대 보관품");
        }
        d.markDirty();
    }

    @Override protected void cleanup() {
        for (Player p : online()) if (!p.isDead()) unstash(plugin, p);
        for (Player p : online()) plugin.wasd().cancel(p);
        seated.clear();
        for (int c = 0; c < chairs.size(); c++) tilt(c, false, 6);
        chairOf.clear();
        for (Entity e : seats) { if (e != null && e.isValid()) { e.eject(); e.remove(); } }
        seats.clear();
    }
}
