package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * chagimmick{type=...;range=16;mana=30} — dungeon gimmick spells 239~250 (+ 35 다시 타오르는 불, 48 심지 줄이기).
 * The work happens in ChacaGimmick (MagicGimmickEvent). When no gimmick took it:
 *  - ignite / extinguish fall back to the vanilla behaviour that used to live in the Bridge SpellUtility
 *    (campfire / candle relight, fire block put out),
 *  - everything else refunds mana + cooldown and tells the caster why.
 */
final class GimmickMech extends Mech {
    GimmickMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false;
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        String spell = p.s("spell", "");
        double range = Math.clamp(p.d("range", 16), 2, 48);
        var ev = new MagicGimmickEvent(pl, type, spell, range, pl.isSneaking());
        if (MagicGimmickEvent.listened()) Bukkit.getPluginManager().callEvent(ev);
        if (ev.isHandled()) return true;
        if (type.equals("ignite") && relight(pl)) return true;
        if (type.equals("extinguish") && extinguish(pl)) return true;
        String msg = ev.getMessage();
        if (msg == null || msg.isBlank()) msg = switch (type) {
            case "ignite" -> "불을 붙일 화로·모닥불·초를 바라보고 써 주세요.";
            case "extinguish" -> "끌 불을 바라보고 써 주세요.";
            default -> "바라보는 곳에 이 마법이 통하는 장치가 없습니다.";
        };
        ChacaLink.notify(pl, "warning", "magic-mythic:gimmick-fail", msg, "", 1500);
        refund(pl, spell);
        return false;
    }

    private void refund(Player pl, String spell) {
        double cost = p.d("mana", 0);
        if (cost > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, cost));
        if (!spell.isBlank()) Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell));
    }

    // ---- vanilla behaviour moved from MagicCodexBridge SpellUtility (route utility:false since 2026-10-20) ----
    private boolean relight(Player p) {
        Block b = p.getTargetBlockExact((int) Math.round(this.p.d("range", 12)));
        if (b == null || !Tag.CAMPFIRES.isTagged(b.getType()) && !Tag.CANDLES.isTagged(b.getType()) && !Tag.CANDLE_CAKES.isTagged(b.getType())) return false;
        if (!(b.getBlockData() instanceof Lightable light) || light.isLit() || b.getBlockData() instanceof Waterlogged w && w.isWaterlogged() || !allowed(p, b)) return false;
        light.setLit(true); b.setBlockData(light);
        return true;
    }
    private boolean extinguish(Player p) {
        Block b = p.getTargetBlock(Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR), (int) Math.round(this.p.d("range", 12)));
        if (b.getType() != Material.FIRE && b.getType() != Material.SOUL_FIRE) return false;
        if (!Targets.canBreak(p, b)) return false;
        b.setType(Material.AIR);
        return true;
    }
    private static boolean allowed(Player p, Block b) {
        if (!MagicMythicAccess.at(p, b.getLocation())) return false;
        var original = b.getBlockData().clone();
        var event = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, p.getInventory().getItemInMainHand(), b, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return event.useInteractedBlock() != Event.Result.DENY && b.getBlockData().equals(original);
    }
}
