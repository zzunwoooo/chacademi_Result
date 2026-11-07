package kr.kkaegul.core.item;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.api.ActionRunner;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Locs;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 커스텀 아이템 우클릭 사용 */
public final class ItemUseListener implements Listener {
    private final KkaegulCore plugin;

    public ItemUseListener(KkaegulCore plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            // 왼손에 든 상자/티켓이 바닐라 블록으로 설치되지 않게
            CustomItem off = plugin.items().type(e.getItem());
            if (off != null && off != CustomItem.COLA && off != CustomItem.KKAEKKAERO) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        CustomItem type = plugin.items().type(hand);
        if (type == null) return;
        if (type == CustomItem.COLA || type == CustomItem.KKAEKKAERO) return; // 먹는 아이템은 바닐라 동작
        e.setCancelled(true);
        Player p = e.getPlayer();
        PlayerData d = plugin.data().get(p);
        switch (type) {
            case NORMAL_BOX, PREMIUM_BOX, LUCKY_BOX -> { consume(p); plugin.boxes().open(p, plugin.boxes().kindOf(type)); }
            case COIN_POUCH -> {
                consume(p);
                int n = ActionRunner.parseRange(plugin.getConfig().getString("items.coin_pouch.coins", "3-8"));
                d.coins += n; d.markDirty();
                plugin.ui().center(p, "&a깨굴코인 +" + n, "&f보유 " + Txt.num(d.coins));
                plugin.hud().refresh(p);
            }
            case RETURN_TICKET -> {   // 귀환권: 로비로 (감옥·미니게임 중 불가)
                if (d.inJail) { plugin.ui().error(p, "&c감옥에서는 귀환권을 쓸 수 없어요."); return; }
                if (plugin.minigames().busy(p)) { plugin.ui().error(p, "&c미니게임 중에는 귀환권을 쓸 수 없어요."); return; }
                Location lobby = plugin.places().get("lobby");
                if (lobby == null) { plugin.ui().error(p, "&c로비 위치가 아직 정해지지 않았어요."); return; }
                consume(p);
                Location from = p.getLocation();
                p.teleport(lobby);
                plugin.ui().teleportSound(p, from);
                plugin.ui().center(p, "&f로비로 귀환!", "");
            }
            case TELEPORT_TICKET -> {
                if (d.inJail) { plugin.ui().error(p, "&c감옥에서는 쓸 수 없어요."); return; }
                List<String> names = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (Player o : plugin.getServer().getOnlinePlayers()) if (!o.equals(p)) { names.add(o.getName()); labels.add(plugin.displayName(o)); }
                if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "player_select").put("data", Json.obj().put("purpose", "user_tp").put("players", names).put("labels", labels))))
                    p.sendMessage(Txt.color(Txt.notice("&b[유저 텔포권] &f/유저텔포 <이름> &7으로 사용 (" + String.join(", ", names) + ")")));
            }
            case GAUGE_TICKET -> {
                // 창 없이: 우클릭 = 위로, 웅크리고 우클릭 = 아래로
                consume(p);
                int step = plugin.getConfig().getInt("items.gauge_ticket.step", 10);
                boolean up = !p.isSneaking();
                plugin.gauge().add(d, up ? step : -step);
                plugin.gauge().announce(p, up ? step : -step);
            }
            case GAUGE_RANDOM -> {
                consume(p);
                int delta = Rng.range(plugin.getConfig().getInt("items.gauge_random.min", -10), plugin.getConfig().getInt("items.gauge_random.max", 10));
                plugin.gauge().add(d, delta);
                plugin.gauge().announce(p, delta);
            }
            case FARM_TICKET -> { consume(p); plugin.economy().rerollFarm(true); }
            case TREASURE_TICKET -> {
                if (d.inJail) { plugin.ui().error(p, "&c감옥에서는 쓸 수 없어요."); return; }
                if (plugin.minigames().busy(p)) { plugin.ui().error(p, "&c미니게임 중에는 쓸 수 없어요."); return; }
                if (plugin.mine().isInMine(p)) { plugin.ui().error(p, "&c이미 광산에 있어요."); return; }
                if (plugin.mine().sendTreasure(p, true)) consume(p);
                else plugin.ui().error(p, "&c보물광산이 아직 준비되지 않았어요. 운영자에게 알려주세요.");
            }
            case INVITE_TICKET -> {   // 초대권: 운영자 호출
                consume(p);
                plugin.chat().callAdmin(p);
            }
            case LAND_TICKET -> { if (plugin.land().claim(p)) consume(p); }   // 땅문서: 서 있는 청크 사기
            case INVEN_SAVE_TICKET -> p.sendMessage(Txt.color(Txt.notice("&e인벤세이브권은 인벤토리에 있으면 죽을 때 1장 자동으로 쓰여서 아이템을 지켜줘요.")));
            case GACHA_PROTECT -> p.sendMessage(Txt.color(Txt.notice("&d가챠 파괴 방지권은 인벤토리에 있으면 파괴 순간 자동으로 사용돼요.")));
            case FAIL_PAPER -> p.sendMessage(Txt.color(Txt.notice("&8꽝 종이 5장을 모아 &f/접속보상 교환 &8으로 고급상자를 받으세요.")));
            default -> { }
        }
    }

    /** 콜라를 마시면 빈 병 대신 아무것도 안 남게 + 콜라·깨깨로는 스테이크만큼 배고픔을 채움 */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        CustomItem t = plugin.items().type(e.getItem());
        if (t == CustomItem.COLA && e.getItem().getType() == Material.HONEY_BOTTLE) {   // 예전(꿀병) 콜라만
            plugin.getServer().getScheduler().runTask(plugin, () -> e.getPlayer().getInventory().removeItem(new ItemStack(Material.GLASS_BOTTLE, 1)));
        }
        if (t == CustomItem.COLA || t == CustomItem.KKAEKKAERO) {
            Player p = e.getPlayer();
            int food = p.getFoodLevel();
            float sat = p.getSaturation();
            int hunger = plugin.getConfig().getInt("items.food-restore.hunger", 8);          // 스테이크: 8 (하트 4칸)
            float s = (float) plugin.getConfig().getDouble("items.food-restore.saturation", 12.8);
            // 바닐라(사과) 값이 들어간 다음 틱에 덮어씀
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                int nf = Math.min(20, food + hunger);
                p.setFoodLevel(nf);
                p.setSaturation(Math.min(nf, sat + s));
            });
        }
    }

    /** 손에 든 아이템 1개 소모 */
    public static void consume(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it == null) return;
        if (it.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else { it.setAmount(it.getAmount() - 1); p.getInventory().setItemInMainHand(it); }
    }

    /** 인벤토리에서 해당 커스텀 아이템 1개 소모 (명령어로 쓰는 티켓용) */
    public static boolean consumeFromInventory(KkaegulCore plugin, Player p, CustomItem type) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            if (!plugin.items().is(c[i], type)) continue;
            if (c[i].getAmount() <= 1) p.getInventory().setItem(i, null);
            else { c[i].setAmount(c[i].getAmount() - 1); p.getInventory().setItem(i, c[i]); }
            return true;
        }
        return false;
    }
}
