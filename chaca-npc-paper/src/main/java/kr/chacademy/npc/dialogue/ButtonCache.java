package kr.chacademy.npc.dialogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 추천 버튼 대답을 NPC·일과표 칸별로 미리 만들어 둔다. 같은 플레이어에게 같은 대답은 다시 주지 않는다.
 */
public final class ButtonCache {

    private final Map<String, List<String>> variants = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seen = new ConcurrentHashMap<>();
    private volatile String day = "";

    private static String key(String npc, int slot, String button) {
        return npc + "|" + slot + "|" + button;
    }

    private void roll(String today) {
        if (!today.equals(day)) {
            variants.clear();
            seen.clear();
            day = today;
        }
    }

    public int size(String today, String npc, int slot, String button) {
        roll(today);
        List<String> l = variants.get(key(npc, slot, button));
        return l == null ? 0 : l.size();
    }

    /** 목표 개수만큼 쌓였을 때만, 이 플레이어가 아직 못 본 대답을 하나 준다. */
    public String take(String today, String npc, int slot, String button, String player, int target) {
        roll(today);
        String k = key(npc, slot, button);
        List<String> list = variants.get(k);
        if (list == null) {
            return null;
        }
        List<String> copy;
        synchronized (list) {
            if (list.size() < target) {
                return null;
            }
            copy = new ArrayList<>(list);
        }
        Collections.shuffle(copy);
        Set<String> s = seen.computeIfAbsent(player + "|" + k, x -> ConcurrentHashMap.newKeySet());
        for (String line : copy) {
            if (s.add(line)) {
                return line;
            }
        }
        return null;
    }

    public void add(String today, String npc, int slot, String button, String line, String player, int target) {
        roll(today);
        List<String> list = variants.computeIfAbsent(key(npc, slot, button), x -> new ArrayList<>());
        synchronized (list) {
            if (list.size() < target && !list.contains(line)) {
                list.add(line);
            }
        }
        if (player != null) {
            seen.computeIfAbsent(player + "|" + key(npc, slot, button), x -> ConcurrentHashMap.newKeySet()).add(line);
        }
    }

    public void clear() {
        variants.clear();
        seen.clear();
    }
}
