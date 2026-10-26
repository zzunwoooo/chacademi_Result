package kr.chacademy.portrait.service;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * ItemsAdder 연동 (리플렉션 — 컴파일 의존성 없음).
 * {@code dev.lone.itemsadder.api.CustomStack.byItemStack(ItemStack).getNamespacedID()} 로 아이템 id를 읽는다.
 */
public final class ItemsAdderHook {

    private final Logger log;
    private Method byItemStack;
    private Method namespacedId;
    private boolean warned;

    public ItemsAdderHook(Logger log) {
        this.log = log;
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") && resolve();
    }

    private boolean resolve() {
        if (byItemStack != null) {
            return true;
        }
        try {
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            byItemStack = cs.getMethod("byItemStack", ItemStack.class);
            namespacedId = cs.getMethod("getNamespacedID");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warned) {
                warned = true;
                log.warning("[ChacaPortrait] ItemsAdder API를 찾지 못했습니다: " + e.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** 이 아이템의 ItemsAdder namespace:id (ItemsAdder 아이템이 아니면 null). 메인 스레드에서 호출. */
    public String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !available()) {
            return null;
        }
        try {
            Object stack = byItemStack.invoke(null, item);
            if (stack == null) {
                return null;
            }
            Object id = namespacedId.invoke(stack);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
