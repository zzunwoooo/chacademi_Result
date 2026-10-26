package school.chacademia.kyudichi;

import java.util.Locale;

/** Order = wire id (KyudichiProtocol). */
enum ShrineType {
    HEALTH("health", "체력"), MANA("mana", "마나"), STABILITY("stability", "안정성"), HASTE("haste", "가속");

    final String key, label;
    ShrineType(String key, String label) { this.key = key; this.label = label; }

    static ShrineType parse(String s) {
        String k = s.toLowerCase(Locale.ROOT).trim();
        for (ShrineType t : values()) if (t.key.equals(k) || t.label.equals(k)) return t;
        return switch (k) { case "안정" -> STABILITY; case "체" , "hp" -> HEALTH; default -> null; };
    }
}
