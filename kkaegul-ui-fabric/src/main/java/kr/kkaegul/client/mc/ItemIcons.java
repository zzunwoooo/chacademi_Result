package kr.kkaegul.client.mc;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 서버가 보내는 아이템 모양 문자열 → ItemStack (UI 아이콘·치장품 렌더 공용).
 * 형식: "재료|m=item_model|f=실수,..|s=문자열,.." (ItemsAdder 아이템 모양, 서버 ItemIcon.of) 또는 재료 id 만 ("WHEAT", "minecraft:wheat").
 * 같은 문자열은 한 번만 만든다 (렌더 스레드 전용).
 */
public final class ItemIcons {
    private ItemIcons() {}

    private static final Map<String, ItemStack> CACHE = new HashMap<>();
    /** 치장품 머리(HEAD) 모양 — 아이콘마다 한 번만 만들어 두고 매 프레임 그대로 그림 (10초마다 새로 만들어 리소스팩 바뀜 반영) */
    private static final Map<String, net.minecraft.client.render.item.ItemRenderState> HEAD_STATES = new HashMap<>();
    private static final Map<String, Long> HEAD_AT = new HashMap<>();

    /** 치장품을 머리 모드로 그릴 준비가 된 모양 (못 만들면 null). 렌더 스레드 전용 */
    public static net.minecraft.client.render.item.ItemRenderState head(String icon) {
        if (icon == null || icon.isEmpty()) return null;
        long now = System.currentTimeMillis();
        net.minecraft.client.render.item.ItemRenderState st = HEAD_STATES.get(icon);
        Long at = HEAD_AT.get(icon);
        if (st == null || at == null || now - at > 10_000) {
            ItemStack s = stack(icon);
            if (st == null) st = new net.minecraft.client.render.item.ItemRenderState();
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            // 인자 7개 update = 비우기 + HEAD 표시 위치 지정
            mc.getItemModelManager().update(st, s, net.minecraft.item.ModelTransformationMode.HEAD, false, mc.world, null, 0);
            if (HEAD_STATES.size() > 500) { HEAD_STATES.clear(); HEAD_AT.clear(); }
            HEAD_STATES.put(icon, st);
            HEAD_AT.put(icon, now);
        }
        return st.isEmpty() ? null : st;
    }

    /** 못 만들면 ItemStack.EMPTY. 돌려준 스택은 캐시 공용이니 고치지 말 것 */
    public static ItemStack stack(String icon) {
        if (icon == null || icon.isEmpty()) return ItemStack.EMPTY;
        ItemStack s = CACHE.get(icon);
        if (s == null) {
            s = parse(icon);
            if (CACHE.size() > 2000) CACHE.clear();
            CACHE.put(icon, s);
        }
        return s;
    }

    private static ItemStack parse(String full) {
        String[] parts = full.split("\\|");
        Identifier key = Identifier.tryParse(parts[0].toLowerCase(Locale.ROOT));
        if (key == null) return ItemStack.EMPTY;
        Item it = Registries.ITEM.get(key);
        if (it == Items.AIR) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(it);
        List<Float> floats = new ArrayList<>();
        List<String> strings = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() < 3 || p.charAt(1) != '=') continue;
            String v = p.substring(2);
            try {
                switch (p.charAt(0)) {
                    case 'm' -> { Identifier m = Identifier.tryParse(v); if (m != null) s.set(DataComponentTypes.ITEM_MODEL, m); }
                    case 'f' -> { for (String f : v.split(",")) floats.add(Float.parseFloat(f)); }
                    case 's' -> strings.addAll(List.of(v.split(",")));
                    default -> { }
                }
            } catch (RuntimeException ignored) { }
        }
        if (!floats.isEmpty() || !strings.isEmpty())
            s.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(floats, List.of(), strings, List.of()));
        return s;
    }
}
