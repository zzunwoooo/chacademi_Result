package school.magiccodex.paper;

import org.bukkit.inventory.ItemStack;

/**
 * 장비창 아이콘 바이트 (512바이트 이하). 서버에 올라간 EquipPreview(2026-10-30, 소스 없음)를 그대로 옮긴 뒤 이름 부분만 고쳤다.
 * 1) 전체 아이템이 512 이하면 그대로. 2) 아니면 같은 종류의 새 아이템에 이름·아이템 모델·커스텀 모델 데이터만 옮겨 담고,
 * 3) 그래도 크면 이름 없이 모델만, 4) 마지막은 맨 종류.
 */
public final class EquipPreview {
    private EquipPreview() {}

    public static byte[] preview(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return new byte[0];
        byte[] bytes = item.serializeAsBytes();
        if (bytes.length <= 512) return bytes;
        var meta = item.getItemMeta();
        for (int attempt = 0; attempt < 2; attempt++) {
            ItemStack small = new ItemStack(item.getType());
            var sm = small.getItemMeta();
            if (sm != null && meta != null) {
                // [기능:alchemy-overhaul 시작] 장비창 이름이 "종이"로 나오던 문제: item.displayName() 은 [이름]+마우스 올림 정보(아이템 전체)라
                // 커서 늘 512를 넘었고, 그래서 이름 없는 다음 단계로 넘어갔다. 이제 아이템의 이름만 옮긴다.
                if (attempt == 0) {
                    if (meta.hasDisplayName()) sm.displayName(meta.displayName());
                    else if (meta.hasItemName()) sm.itemName(meta.itemName());
                }
                // [기능:alchemy-overhaul 끝]
                try { if (meta.hasItemModel()) sm.setItemModel(meta.getItemModel()); } catch (RuntimeException | LinkageError ignored) { }
                try { if (meta.hasCustomModelData()) sm.setCustomModelData(meta.getCustomModelData()); } catch (RuntimeException | LinkageError ignored) { }
                small.setItemMeta(sm);
            }
            bytes = small.serializeAsBytes();
            if (bytes.length <= 512) return bytes;
        }
        bytes = new ItemStack(item.getType()).serializeAsBytes();
        return bytes.length > 512 ? new byte[0] : bytes;
    }
}
