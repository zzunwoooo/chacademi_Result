package school.chacademia.gimmick;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Admin wand. Click a model (or look at it and click air) to select and edit it.
 *  right click = + / next      left click = - / previous
 *  sneak + right click = next mode      sneak + left click = previous mode
 * Modes: 정보, 회전(90도), 크기, 이동(바라보는 방향 축으로 1칸), 높이(1칸), 정답, 삭제
 * 정답 = what an adjustable device has to show to count as on (수위 기둥 수위, 회전 다이얼 위치, 잠든 석상 방향,
 * 빛 수신 수정 색) or where a floating platform goes (앞으로/위로 N칸).
 */
final class DebugTool implements Listener {
    enum Mode { INFO("정보"), ROTATE("회전"), SCALE("크기"), MOVE("이동"), HEIGHT("높이"), ANSWER("정답"), DELETE("삭제");
        final String label; Mode(String l) { label = l; } }

    private final GimmickPlugin plugin;
    private final NamespacedKey toolKey;
    private final Map<UUID, Mode> modes = new HashMap<>();
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, Long> deleteArm = new HashMap<>();

    DebugTool(GimmickPlugin plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "debug_tool");
    }

    ItemStack create() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("tool.material", "BLAZE_ROD"));
        ItemStack item = new ItemStack(m == null ? Material.BLAZE_ROD : m);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("기믹 도구", NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("우클릭 + / 좌클릭 -", NamedTextColor.GRAY),
            Component.text("웅크리고 클릭: 모드 바꾸기", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    boolean isTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    Gimmick selection(Player p) {
        String id = selected.get(p.getUniqueId());
        Gimmick g = id == null ? null : plugin.world().get(id);
        return g != null ? g : plugin.world().looking(p, 12);
    }
    void select(Player p, Gimmick g) { if (g != null) selected.put(p.getUniqueId(), g.id); }

    /** Clicks in the air / on blocks: pick what the player looks at, else the current selection. */
    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isTool(e.getItem())) return;
        Player p = e.getPlayer();
        if (!p.isOp()) return;
        e.setCancelled(true);
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        Gimmick g = plugin.world().looking(p, 12);
        if (g == null) {
            String id = selected.get(p.getUniqueId());
            g = id == null ? null : plugin.world().get(id);
        }
        if (p.isSneaking()) { cycle(p, right); return; }
        if (g == null) { bar(p, "선택된 기믹이 없습니다. 모델을 바라보고 클릭하세요."); return; }
        apply(p, g, right);
    }

    /** Called by Inputs when a gimmick entity itself was clicked with the tool. */
    void use(Player p, Gimmick g, boolean right) {
        if (!p.isOp()) return;
        if (p.isSneaking()) { cycle(p, right); return; }
        apply(p, g, right);
    }

    private void cycle(Player p, boolean forward) {
        Mode[] all = Mode.values();
        Mode m = modes.getOrDefault(p.getUniqueId(), Mode.INFO);
        Mode next = all[(m.ordinal() + (forward ? 1 : all.length - 1)) % all.length];
        modes.put(p.getUniqueId(), next);
        deleteArm.remove(p.getUniqueId());
        bar(p, "모드: " + next.label);
    }

    private void apply(Player p, Gimmick g, boolean right) {
        if(!plugin.protection().pair(p,g.location())){bar(p,"보호 구역 안의 기믹입니다.");return;}
        boolean changedSelection = !g.id.equals(selected.get(p.getUniqueId()));
        select(p, g);
        Mode m = modes.getOrDefault(p.getUniqueId(), Mode.INFO);
        var cfg = plugin.getConfig();
        int sign = right ? 1 : -1;
        switch (m) {
            case INFO -> { info(p, g); return; }
            case ROTATE -> {
                if (g.attached()) {
                    g.yaw = (float) normalize(g.yaw + sign * 90);
                    var host = Bukkit.getEntity(g.host); if (host != null) host.setRotation(g.yaw, 0);
                } else {
                    var info = plugin.catalog().get(g.model);
                    int old = g.facing; g.facing = Math.floorMod(g.facing + sign, 4);
                    if (blocked(p, g, info)) { g.facing = old; return; }
                }
            }
            case SCALE -> g.scale = Math.clamp(Math.round((g.scale + sign * cfg.getDouble("tool.scale-step", 0.1)) * 1000) / 1000.0, 0.1, 8);
            case MOVE -> {
                if (g.attached()) { bar(p, "몹에 씌운 모델은 이동할 수 없습니다."); return; }
                Vector axis = axis(p.getLocation().getDirection()).multiply(sign);
                g.bx += axis.getBlockX(); g.bz += axis.getBlockZ();
                if (blocked(p, g, plugin.catalog().get(g.model))) { g.bx -= axis.getBlockX(); g.bz -= axis.getBlockZ(); return; }
            }
            case HEIGHT -> {
                if (g.attached()) { bar(p, "몹에 씌운 모델은 이동할 수 없습니다."); return; }
                g.by += sign;
                if (blocked(p, g, plugin.catalog().get(g.model))) { g.by -= sign; return; }
            }
            case ANSWER -> { answer(p, g, sign); return; }
            case DELETE -> {
                long now = plugin.signals().now();
                Long armed = deleteArm.get(p.getUniqueId());
                if (!right) { deleteArm.remove(p.getUniqueId()); bar(p, "삭제 취소"); return; }
                if (armed == null || armed < now || changedSelection) {
                    deleteArm.put(p.getUniqueId(), now + 60);
                    bar(p, g.id + " 삭제하려면 3초 안에 한 번 더 우클릭");
                    return;
                }
                deleteArm.remove(p.getUniqueId());
                plugin.world().remove(g);
                selected.remove(p.getUniqueId());
                bar(p, g.id + " 삭제됨");
                return;
            }
        }
        plugin.world().transform(g);
        bar(p, g.attached() ? String.format(Locale.ROOT, "%s  %s  방향 %.0f°  크기 %.2f", m.label, g.id, g.yaw, g.scale)
            : String.format(Locale.ROOT, "%s  %s  방향 %s  크기 %.2f  블록 %d, %d, %d", m.label, g.id, FACING[g.facing], g.scale, g.bx, g.by, g.bz));
    }
    static final String[] FACING = {"남(0°)", "서(90°)", "북(180°)", "동(270°)"};

    /** Another gimmick already takes one of the blocks (the grid is one model per block). */
    boolean blocked(Player p, Gimmick g, ModelCatalog.Info info) {
        Gimmick other = plugin.world().overlap(g.world, g.columns(info), g.by, g.layers(info), g);
        if (other == null) return false;
        bar(p, "그 자리에는 이미 다른 기믹(" + other.id + ")이 있습니다.");
        return true;
    }

    private void answer(Player p, Gimmick g, int sign) {
        String kind = plugin.catalog().get(g.model).kind();
        if (g.attached() || !Signals.hasAnswer(kind)) { bar(p, "정답이 있는 기믹이 아닙니다 (수위 기둥·회전 다이얼·잠든 석상·빛 수신 수정·부유 발판)"); return; }
        if (kind.equals("platform") && g.travel > 0) { bar(p, "움직인 발판입니다. /던전관리 초기화 로 제자리에 돌린 뒤 바꾸세요."); return; }
        g.answer = Math.floorMod(g.answer + sign, Signals.answerCount(kind));
        plugin.world().markDirty();
        plugin.signals().dirty();
        bar(p, "정답  " + g.id + "  " + Signals.answerLabel(kind, g.answer));
    }

    void info(Player p, Gimmick g) {
        var info = plugin.catalog().get(g.model);
        var sig = plugin.signals();
        p.sendMessage(Component.text("[기믹] " + g.id + " " + info.name() + " (" + g.model + ")  " + Signals.role(info.kind()) + "  "
            + (sig.on(g) ? "켜짐" : "꺼짐"), NamedTextColor.GOLD));
        p.sendMessage(Component.text(g.attached() ? String.format(Locale.ROOT, " (몹에 씌움)  방향 %.0f°  크기 %.2f", g.yaw, g.scale)
            : String.format(Locale.ROOT, " 블록 %s %d, %d, %d  방향 %s  크기 %.2f  차지 %dx%d칸 (%s)", g.world, g.bx, g.by, g.bz, FACING[g.facing], g.scale,
                info.footW(), info.footD(), info.collision()), NamedTextColor.GRAY));
        var lines = new ArrayList<String>();
        switch (info.kind()) {
            case "mirror", "dial" -> lines.add("위치 " + g.pos);
            case "pillar" -> lines.add("수위 " + g.level);
            case "prism" -> lines.add("색 " + Colors.ko(g.color));
            case "conduit" -> lines.add("포트 " + info.ports() + (sig.powered(g) ? "  신호 흐름" : ""));
            default -> {}
        }
        if (Signals.hasAnswer(info.kind())) lines.add("정답 " + Signals.answerLabel(info.kind(), g.answer));
        if (Signals.OUTPUTS.contains(info.kind())) {
            var ins = sig.inputsOf(g);
            long onCount = ins.stream().map(plugin.world()::get).filter(o -> o != null && sig.on(o)).count();
            lines.add(ins.isEmpty() ? "연결된 입력 없음 (입력 블럭이나 도관을 붙이세요)" : "연결된 입력 " + ins.size() + "개 중 " + onCount + "개 켜짐  " + ins);
        }
        if (Signals.INPUTS.contains(info.kind())) {
            var outs = sig.outputsOf(g);
            lines.add(outs.isEmpty() ? "연결된 출력 없음" : "연결된 출력 " + outs);
        }
        if (!lines.isEmpty()) p.sendMessage(Component.text(" " + String.join("  ·  ", lines), NamedTextColor.GRAY));
    }

    private static Vector axis(Vector look) {
        if (Math.abs(look.getX()) > Math.abs(look.getZ())) return new Vector(Math.signum(look.getX()), 0, 0);
        return new Vector(0, 0, Math.signum(look.getZ()));
    }
    private static double normalize(double yaw) { yaw %= 360; if (yaw > 180) yaw -= 360; if (yaw <= -180) yaw += 360; return yaw; }
    private static void bar(Player p, String text) { p.sendActionBar(Component.text(text, NamedTextColor.YELLOW)); }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        modes.remove(id); selected.remove(id); deleteArm.remove(id);
    }
}
