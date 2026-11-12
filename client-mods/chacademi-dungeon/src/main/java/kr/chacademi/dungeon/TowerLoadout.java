package kr.chacademi.dungeon;

// [기능:탑마법키 시작]
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 탑 안 마법 키 (오너 11/12): 유저가 쓰던 마법 키는 그대로 두고, 그 키들에 탑에서 배운 마법을 왼쪽(HUD 순서)부터 채운다.
 * 키가 모자라면 큐디치 경기와 같은 남는 키(Q·E·R·T·G·V·B 중 안 쓰는 것)를 빌린다. 탑이 끝나면 원래 키 배치로 (마법도감의 refreshBindings).
 * 마법도감 모드(MagicCodex)의 저장된 키 설정 파일은 건드리지 않는다 — 화면·입력에 쓰는 배치만 잠깐 바꿈 (큐디치 경기 배치와 같은 방식).
 * 마법도감 클래스는 리플렉션으로 (없거나 모양이 다르면 조용히 끔).
 */
final class TowerLoadout {
    private TowerLoadout() {}

    private static boolean active, broken, warned;
    private static List<String> spells = List.of();
    private static final int[] SPARE = {81, 69, 82, 84, 71, 86, 66};

    private static Method state, base, refresh, ordered, keyCode, setDirect, bindings, inputAllowed, allowed, uiKey;
    private static Constructor<?> binding;
    private static int limit = 6;

    static void set(boolean on, List<String> list) {
        boolean was = active;
        active = on;
        spells = list == null ? List.of() : List.copyOf(list);
        if (was && !on) restore();
    }

    static void clear() { set(false, null); }

    private static boolean link() {
        if (state != null) return true;
        if (broken) return false;
        try {
            var cc = Class.forName("school.magiccodex.client.CastingClient");
            var ks = Class.forName("school.magiccodex.client.SpellKeySettings");
            var bd = Class.forName("school.magiccodex.client.SpellKeySettings$Binding");
            var cs = Class.forName("school.magiccodex.client.CastingState");
            state = cc.getMethod("state");
            base = cc.getMethod("base");
            refresh = cc.getMethod("refreshBindings");
            ordered = ks.getMethod("orderedSlots");
            limit = ks.getField("LIMIT").getInt(null);
            keyCode = bd.getMethod("keyCode");
            binding = bd.getConstructor(String.class, int.class);
            setDirect = cs.getMethod("setBindingsDirect", List.class);
            bindings = cs.getMethod("bindings");
            inputAllowed = cs.getMethod("inputAllowed", boolean.class);
            allowed = Class.forName("school.magiccodex.client.KeySettingsLayout").getMethod("allowed", int.class);
            uiKey = Class.forName("school.magiccodex.client.UiKeys").getMethod("isUiKey", int.class);
            return true;
        } catch (Throwable e) {
            broken = true;
            org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("탑 마법 키 연결 실패 (마법도감 모드 모양이 다름): {}", e.toString());
            return false;
        }
    }

    /** 매 틱: 배치가 다르면 다시 맞춘다 (마법도감이 프리셋 바꾸기 등으로 원래 배치로 돌려놔도 탑 안에선 다시 탑 배치). */
    static void tick() {
        if (!active || !link()) return;
        try {
            Object st = state.invoke(null), b = base.invoke(null);
            if (st == null || b == null) return;   // 마법도감이 아직 키 설정을 안 읽음
            var keys = new ArrayList<Integer>();
            for (Object slot : (List<?>) ordered.invoke(b)) { int k = (int) keyCode.invoke(slot); if (k >= 0 && (boolean) allowed.invoke(null, k)) keys.add(k); }
            for (int k : SPARE) {
                if (keys.size() >= Math.min(limit, spells.size())) break;
                if (!keys.contains(k) && (boolean) allowed.invoke(null, k) && !(boolean) uiKey.invoke(null, k)) keys.add(k);
            }
            var next = new ArrayList<Object>(limit);
            for (int i = 0; i < limit; i++) next.add(binding.newInstance(i < spells.size() ? spells.get(i) : "", i < keys.size() ? keys.get(i) : -1));
            if (!next.equals(bindings.invoke(st))) {
                setDirect.invoke(st, next);
                inputAllowed.invoke(st, true);   // 일반 프리셋을 OFF 로 둔 사람도 탑 안에선 마법이 나가게
            }
        } catch (Throwable e) {
            if (!warned) { warned = true; org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("탑 마법 키 바꾸기 실패: {}", e.toString()); }
        }
    }

    private static void restore() {
        if (!link()) return;
        try { refresh.invoke(null); } catch (Throwable e) { org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("마법 키 되돌리기 실패: {}", e.toString()); }
    }
}
// [기능:탑마법키 끝]
