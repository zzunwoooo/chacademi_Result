package kr.kkaegul.core.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** 아이템 → 모드가 같은 모양으로 그리는 아이콘 문자열 "재료|m=item_model|f=cmd 실수,..|s=cmd 문자열,.." (ItemsAdder 아이템 포함) */
public final class ItemIcon {
    private ItemIcon() {}

    public static String of(ItemStack it) {
        if (it == null || it.getType().isAir()) return "";
        StringBuilder icon = new StringBuilder(it.getType().getKey().toString());
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return icon.toString();
        try {
            if (meta.hasItemModel() && meta.getItemModel() != null) icon.append("|m=").append(meta.getItemModel());
            if (meta.hasCustomModelData()) {
                var cmd = meta.getCustomModelDataComponent();
                if (!cmd.getFloats().isEmpty()) icon.append("|f=").append(String.join(",", cmd.getFloats().stream().map(String::valueOf).toList()));
                if (!cmd.getStrings().isEmpty()) icon.append("|s=").append(String.join(",", cmd.getStrings()));
            }
        } catch (LinkageError | RuntimeException ignored) { }
        return icon.toString();
    }
}
