package kr.kkaegul.core.api;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Colla-Bot mc-donation (donation-bukkit 2.x) 연동.
 * 그 플러그인은 방송인 클라 모드에서 받은 후원을 {@code bot.colla.donation.event.DonationEvent} 로 쏘고,
 * 취소되지 않으면 콘솔 명령 {@code api <방송인> <타입번호> <금액> <후원자>} 를 실행한다.
 * 여기서 이벤트를 받아 별풍선 개수 = API 번호로 바꿔 실행하고, 원래 api 명령은 막는다(취소).
 *
 * - 후원 받은 사람이 OP(또는 donation.server-owners)면 섭주 API(api.server.N), 아니면 그 사람 개인 API(api.personal.N)
 * - SOOP: amount = 별풍선 개수 그대로 / CHZZK: amount(치즈=원) ÷ donation.chzzk-won-per-unit(기본 100)
 * - config 에 없는 개수는 무시(로그만). 컴파일 의존성 없이 리플렉션으로 붙는다.
 */
public final class DonationHook implements Listener {
    private static final String EVENT = "bot.colla.donation.event.DonationEvent";
    private final KkaegulCore plugin;
    private Method getPlayer, getType, getAmount, getNickname, isTest, setCancelled, getPlatform, platformName;

    private DonationHook(KkaegulCore plugin) { this.plugin = plugin; }

    public static boolean install(KkaegulCore plugin) {
        if (!plugin.getConfig().getBoolean("donation.enabled", true)) return false;
        Plugin dp = Bukkit.getPluginManager().getPlugin("donation");
        if (dp == null) { plugin.getLogger().info("donation 플러그인 없음 → 후원 연동 꺼짐 (/kapi 수동 사용)"); return false; }
        try {
            Class<?> raw = Class.forName(EVENT, true, dp.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return false;
            @SuppressWarnings("unchecked") Class<? extends Event> type = (Class<? extends Event>) raw;
            DonationHook h = new DonationHook(plugin);
            h.getPlayer = raw.getMethod("getPlayer");
            h.getType = raw.getMethod("getType");
            h.getAmount = raw.getMethod("getAmount");
            h.getNickname = raw.getMethod("getNickname");
            h.isTest = raw.getMethod("isTestDonation");
            h.setCancelled = raw.getMethod("setCancelled", boolean.class);
            Class<?> typeEnum = h.getType.getReturnType();
            h.getPlatform = typeEnum.getMethod("getPlatform");
            h.platformName = Enum.class.getMethod("name");
            Bukkit.getPluginManager().registerEvent(type, h, EventPriority.NORMAL, (l, e) -> {
                if (raw.isInstance(e)) h.handle(e);
            }, plugin, false);
            plugin.getLogger().info("donation 플러그인 연동 완료");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("donation 연동 실패: " + ex);
            return false;
        }
    }

    private void handle(Event e) {
        try {
            Player target = (Player) getPlayer.invoke(e);
            Object type = getType.invoke(e);
            int amount = (Integer) getAmount.invoke(e);
            String donor = String.valueOf(getNickname.invoke(e));
            boolean test = (Boolean) isTest.invoke(e);
            String platform = String.valueOf(platformName.invoke(getPlatform.invoke(type)));
            String typeName = String.valueOf(platformName.invoke(type));

            if (test && !plugin.getConfig().getBoolean("donation.allow-test", true)) return;
            List<String> ignore = plugin.getConfig().getStringList("donation.ignore-types");
            if (ignore.contains(typeName)) return;

            int units = platform.equals("CHZZK") ? amount / Math.max(1, plugin.getConfig().getInt("donation.chzzk-won-per-unit", 100)) : amount;
            String number = String.valueOf(units);
            boolean server = isServerOwner(target);
            boolean known = server ? !plugin.api().serverActions(number).isEmpty() : !plugin.api().personalActions(number).isEmpty();
            if (!known) {
                plugin.getLogger().info("[후원] " + target.getName() + " ← " + donor + " " + platform + " " + amount + " (" + units + "개) : 등록된 API 없음");
                if (plugin.getConfig().getBoolean("donation.cancel-unknown", true)) setCancelled.invoke(e, true);
                return;
            }
            if (plugin.getConfig().getBoolean("donation.cancel-original", true)) setCancelled.invoke(e, true);
            String src = "후원 " + donor + " " + units + "개";
            if (plugin.getConfig().getBoolean("donation.announce", true)) {
                String fmt = plugin.getConfig().getString("donation.announce-format", "&8[&d후원&8] &f{donor}님이 &e{target}&f님에게 &d{count}개 &7→ {label}");
                String label = plugin.getConfig().getString("api.labels." + (server ? "server." : "personal.") + number, number);
                plugin.announce(Txt.fmt(fmt, "donor", donor, "target", plugin.displayName(target), "count", units, "label", label), true);
            }
            String result = server ? plugin.api().server(number, src) : plugin.api().personal(target.getName(), number, src);
            plugin.getLogger().info("[후원] " + Txt.strip(Txt.color(result)));
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException ex) {
            plugin.getLogger().warning("후원 이벤트 처리 실패: " + ex);
        }
    }

    /** 섭주 = OP (server-owners 에 따로 적은 계정도 포함). OP 가 아닌 사람이 받은 후원은 그 사람 개인 API */
    private boolean isServerOwner(Player p) {
        if (p.isOp()) return true;
        for (String s : plugin.getConfig().getStringList("donation.server-owners"))
            if (s.equalsIgnoreCase(p.getName()) || s.equalsIgnoreCase(p.getUniqueId().toString())) return true;
        return false;
    }
}
