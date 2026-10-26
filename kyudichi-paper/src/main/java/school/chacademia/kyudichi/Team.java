package school.chacademia.kyudichi;

import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;

/** A = blue, B = red (team colours are never house colours). Order = wire id. */
enum Team {
    A("A", "파랑", "파랑 팀", NamedTextColor.BLUE), B("B", "빨강", "빨강 팀", NamedTextColor.RED);

    /** key = arenas.yml 저장 키(바꾸지 않음), word = 명령어 칸에 쓰는 한글. */
    final String key, word, label; final NamedTextColor color;
    Team(String key, String word, String label, NamedTextColor color) { this.key = key; this.word = word; this.label = label; this.color = color; }
    Team other() { return this == A ? B : A; }

    static Team parse(String s) {
        return switch (s.toUpperCase(Locale.ROOT).trim()) { case "A", "파랑", "블루" -> A; case "B", "빨강", "레드" -> B; default -> null; };
    }
}
