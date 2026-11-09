package kr.kkaegul.core.item;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * ItemsAdder 연동 (리플렉션 — ItemsAdder 가 없으면 자동으로 꺼지고 바닐라 아이템으로 대체).
 * 아이템 id 는 "item:<CustomItem.id>" (config items.itemsadder-namespace 로 변경 가능).
 * 사용 API: dev.lone.itemsadder.api.CustomStack#getInstance(String), #getItemStack(), #byItemStack(ItemStack), #getNamespacedID()
 */
public final class ItemsAdderHook {
    private final KkaegulCore plugin;
    private Method getInstance, getItemStack, byItemStack, getNamespacedID;
    private boolean ready = false;

    public ItemsAdderHook(KkaegulCore plugin) {
        this.plugin = plugin;
        try {
            if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return;
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            getInstance = cs.getMethod("getInstance", String.class);
            getItemStack = cs.getMethod("getItemStack");
            byItemStack = cs.getMethod("byItemStack", ItemStack.class);
            getNamespacedID = cs.getMethod("getNamespacedID");
            ready = true;
            plugin.getLogger().info("ItemsAdder 연동 켜짐 (네임스페이스 " + namespace() + ")");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("ItemsAdder API 를 찾지 못해 바닐라 아이템을 씁니다: " + e);
        }
    }

    public boolean enabled() { return ready && plugin.getConfig().getBoolean("items.use-itemsadder", true); }

    public String namespace() { return plugin.getConfig().getString("items.itemsadder-namespace", "item"); }

    /** ItemsAdder 아이템 생성. 없거나 아직 로딩 전이면 null */
    public ItemStack create(String id, int amount) {
        if (!enabled()) return null;
        try {
            Object stack = getInstance.invoke(null, namespace() + ":" + id);
            if (stack == null) return null;
            ItemStack it = ((ItemStack) getItemStack.invoke(stack)).clone();
            it.setAmount(Math.max(1, amount));
            return it;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 다른 네임스페이스 포함 아무 ItemsAdder 아이템 ("addcrop:addcrop_tomato"). 없으면 null */
    public ItemStack createAny(String namespacedId, int amount) {
        if (!ready) return null;
        try {
            Object stack = getInstance.invoke(null, namespacedId);
            if (stack == null) return null;
            ItemStack it = ((ItemStack) getItemStack.invoke(stack)).clone();
            it.setAmount(Math.max(1, amount));
            return it;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** ItemsAdder 아이템이면 네임스페이스 포함 id, 아니면 null */
    public String namespacedIdOf(ItemStack it) {
        if (!ready || it == null) return null;
        try {
            Object stack = byItemStack.invoke(null, it);
            return stack == null ? null : (String) getNamespacedID.invoke(stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** ItemsAdder 아이템이면 "<id>" (네임스페이스 제외), 우리 네임스페이스가 아니거나 아니면 null */
    public String idOf(ItemStack it) {
        if (!ready || it == null) return null;
        try {
            Object stack = byItemStack.invoke(null, it);
            if (stack == null) return null;
            String nid = (String) getNamespacedID.invoke(stack);
            String prefix = namespace() + ":";
            return nid != null && nid.startsWith(prefix) ? nid.substring(prefix.length()) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
