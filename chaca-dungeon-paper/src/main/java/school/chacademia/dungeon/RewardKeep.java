package school.chacademia.dungeon;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/**
 * [기능:던전보상능력치유지 시작]
 * 2026-10-30 오너: 능력치를 붙인 반지를 던전 보상으로 넣었더니 보상 목록에 이름만 저장됨. 보상 추가(명령·설정 창)는 ItemsAdder 아이템이면
 * id(item:ring_2_1)만 저장해서 지능·마나·가속(MagicCodexBridge 표시) 같은 데이터가 빠졌다.
 * ItemsAdder 가 아닌 다른 플러그인 데이터(PDC)가 붙어 있으면 id 대신 아이템 그대로(Base64) 저장하게 null 을 돌려준다.
 * AdminCommands·Editor 의 보상 추가에서 items.idOf(hand) 직후 (배포 jar 는 ASM 이었고, 2026-10-31 소스로 옮김).
 * [기능:던전보상능력치유지 끝]
 */
public final class RewardKeep {
    private RewardKeep() {}
    public static String ia(String ia, ItemStack hand) {
        try {
            if (ia == null || hand == null || !hand.hasItemMeta()) return ia;
            for (NamespacedKey k : hand.getItemMeta().getPersistentDataContainer().getKeys())
                if (!k.getNamespace().equals("itemsadder")) return null;
            return ia;
        } catch (RuntimeException e) { return ia; }
    }
}
