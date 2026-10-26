package school.magiccodex.paper;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * ItemsAdder items by namespaced id (e.g. "item:random_artifact"), through reflection so ItemsAdder stays optional
 * (same approach as chaca-portrait's ItemsAdderHook). Main thread only.
 */
final class ItemsAdderItems {
    private final Logger log;
    private Method byItemStack, namespacedId, getInstance, getItemStack;
    private boolean resolved, warned;

    ItemsAdderItems(Logger log) { this.log = log; }

    boolean available() { return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") && resolve(); }

    private boolean resolve() {
        if (resolved) return byItemStack != null;
        resolved = true;
        try {
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            byItemStack = cs.getMethod("byItemStack", ItemStack.class);
            namespacedId = cs.getMethod("getNamespacedID");
            getInstance = cs.getMethod("getInstance", String.class);
            getItemStack = cs.getMethod("getItemStack");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            byItemStack = null;
            if (!warned) { warned = true; log.warning("ItemsAdder API를 찾지 못했습니다: " + e.getClass().getSimpleName()); }
            return false;
        }
    }

    /** namespace:id of this ItemsAdder item, or null. */
    String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !available()) return null;
        try {
            Object stack = byItemStack.invoke(null, item);
            return stack == null ? null : String.valueOf(namespacedId.invoke(stack));
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    /** A new stack of this ItemsAdder item, or null when it does not exist. */
    ItemStack create(String id, int amount) {
        if (!available()) return null;
        try {
            Object stack = getInstance.invoke(null, id);
            if (stack == null) return null;
            ItemStack item = ((ItemStack) getItemStack.invoke(stack)).clone();
            item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
            return item;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
}
