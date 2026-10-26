package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 스토리 단계(ChacademyStory 플러그인) 연결 — 길잡이 별이 퀘스트 목표를 가리키게 한다. 컴파일 의존 없이 리플렉션.
 * ChacademyStory 가 없거나 예전 버전이면 0 (가리킬 퀘스트 없음).
 *
 * ChacademyStory StoryPlugin 쪽 공개 메서드:
 *   int questGuide(Player)  — 0 없음 · 1 화살표 시작 · 2 목표가 다른 세계 · 3 이미 목표 근처(10블록) · 4 화살표 끔(다시 시전)
 */
final class StoryLink {
    static final int NONE = 0, OK = 1, OTHER_WORLD = 2, NEAR = 3, OFF = 4;
    private static Plugin cachedPlugin;
    private static Method guide;

    private StoryLink() {}

    static int guide(Player player) {
        Plugin p = Bukkit.getPluginManager().getPlugin("ChacademyStory");
        if (p == null || !p.isEnabled()) return NONE;
        if (p != cachedPlugin) {
            try {
                guide = p.getClass().getMethod("questGuide", Player.class);
                cachedPlugin = p;
            } catch (ReflectiveOperationException | LinkageError e) {
                cachedPlugin = null;
                return NONE;
            }
        }
        try {
            Object r = guide.invoke(p, player);
            return r instanceof Integer i ? i : NONE;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NONE;
        }
    }
}
