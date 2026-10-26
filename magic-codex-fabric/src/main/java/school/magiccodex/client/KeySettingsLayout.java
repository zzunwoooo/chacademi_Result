package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import school.magiccodex.client.CodexHitboxes.Rect;

/** Measurements on key_settings_base.png, shared by drawing and hit testing. */
public final class KeySettingsLayout {
    private KeySettingsLayout() {}
    public record Key(int code, String label, Rect box) {}
    public static final Rect ENTRY = new Rect(865, 74, 200, 48);
    public static final Rect CLOSE = new Rect(1478, 65, 54, 50);
    public static final Rect SAVE = new Rect(1206, 613, 150, 52);
    public static final Rect RESET = new Rect(1370, 613, 143, 52);
    // Picker (owner 2026-10-17): UI shortcut menu on the left (UI_PANEL), the spell list smaller on the right (SPELL_PANEL).
    public static final Rect UI_PANEL = new Rect(300, 162, 262, 635);
    public static final Rect SPELL_PANEL = new Rect(578, 162, 794, 635);
    public static final Rect PREVIOUS = new Rect(598, 742, 95, 40);
    public static final Rect NEXT = new Rect(1257, 742, 95, 40);
    public static final Rect PICKER_CLOSE = new Rect(1316, 170, 48, 48);
    /** 11 UI shortcuts (지도 2026-10-24, 내 땅 2026-10-26) fit the panel (162..797): 48 px apart, 44 px tall (last ends 778). */
    public static Rect uiChoice(int index) { return new Rect(316, 256 + index * 48, 230, 44); }
    public static final Rect DISCARD = new Rect(845, 510, 220, 50);
    public static final Rect CONTINUE = new Rect(605, 510, 220, 50);
    /** Keyboard area of key_settings_base.png and where/how big it is drawn (keys are baked into that art). */
    static final Rect KEYBOARD_SRC = new Rect(212, 158, 1248, 415);
    static final float KEYBOARD_SCALE = .84f, KEYBOARD_X = 212, KEYBOARD_Y = 191;
    public static final List<Key> KEYS = keys();
    // Owner 2026-10-17: only WASD is locked. Every other key may hold a spell or a UI shortcut (UiKeys), one thing per key.
    private static final Set<Integer> RESERVED = Set.of(87, 65, 83, 68);
    private static java.util.function.IntPredicate equipmentKey=code->false;
    static void equipmentKey(java.util.function.IntPredicate predicate){equipmentKey=predicate;}
    private static java.util.function.IntPredicate artifactKey=code->false;
    static void artifactKey(java.util.function.IntPredicate predicate){artifactKey=predicate;}
    static boolean equipmentReserved(int code){return code==79||equipmentKey.test(code);}
    /** X and the (possibly rebound) artifact use key. */
    static boolean artifactReserved(int code){return code==88||artifactKey.test(code);}
    /** WASD (movement) only. */
    public static boolean reservedShortcut(int code){return RESERVED.contains(code);}
    /** Shown when a reserved key is refused. */
    public static final String RESERVED_MESSAGE="WASD는 이동 키라 지정할 수 없습니다.";
    // Positive values outside GLFW keyboard range preserve version-1 integer storage.
    public static final int THUMB_1=1003, THUMB_2=1004;
    public static boolean mouse(int code){return code==THUMB_1||code==THUMB_2;}
    public static int mouseCode(int button){return button==3?THUMB_2:button==4?THUMB_1:-1;}
    public static int mouseButton(int code){return code==THUMB_1?4:code==THUMB_2?3:-1;}
    public static boolean allowed(int code) {
        if(mouse(code))return true;
        return !RESERVED.contains(code) && KEYS.stream().anyMatch(k -> k.code() == code);
    }
    public static String name(int code) {
        if(mouse(code))return code==THUMB_1?"\uc5c4\uc9c0 1":"\uc5c4\uc9c0 2";
        return KEYS.stream().filter(k -> k.code() == code).map(Key::label).findFirst().orElse("미지정");
    }
    public static Rect slot(int index) { return new Rect(375 + index * 166,622,92,88); }
    public static Rect slotKey(int index) { return new Rect(376 + index * 166,729,90,30); }
    public static Rect choice(int index) { return new Rect(598 + index % 3 * 254, 257 + index / 3 * 148, 240,132); }
    private static List<Key> keys() {
        var keys = new ArrayList<Key>();
        row(keys,179,69,new int[]{251,351,437,522,608,692,778,862,948,1033,1118,1204,1290},
                new int[]{89,75,75,73,73,73,72,74,74,74,76,75,151},
                new String[]{"1","2","3","4","5","6","7","8","9","0","-","=","Backspace"},
                new int[]{49,50,51,52,53,54,55,56,57,48,45,61,259});
        row(keys,260,66,new int[]{232,332,410,492,574,656,740,824,907,991,1074,1158,1242,1326},
                new int[]{90,67,72,72,72,73,73,73,73,73,73,72,72,115},
                new String[]{"Tab","Q","W","E","R","T","Y","U","I","O","P","[","]","\\"},
                new int[]{258,81,87,69,82,84,89,85,73,79,80,91,93,92});
        row(keys,336,66,new int[]{232,361,444,527,610,694,778,862,947,1031,1114,1199,1286},
                new int[]{118,73,73,72,73,72,73,73,72,71,74,73,155},
                new String[]{"Caps Lock","A","S","D","F","G","H","J","K","L",";","'","Enter"},
                new int[]{280,65,83,68,70,71,72,74,75,76,59,39,257});
        row(keys,413,65,new int[]{232,406,490,574,658,742,826,910,994,1078,1162,1248},
                new int[]{162,73,73,73,73,73,73,73,73,74,73,193},
                new String[]{"Shift","Z","X","C","V","B","N","M",",",".","/","Shift"},
                new int[]{340,90,88,67,86,66,78,77,44,46,47,344});
        row(keys,488,65,new int[]{232,344,439,538,1041,1145,1241,1344},
                new int[]{100,83,88,493,93,84,91,97},
                new String[]{"Ctrl","Win","Alt","Space","Alt","Win","Menu","Ctrl"},
                new int[]{341,343,342,32,346,347,348,345});
        return List.copyOf(keys);
    }
    private static void row(List<Key> keys,int y,int h,int[] xs,int[] ws,String[] labels,int[] codes) {
        // Original key_settings_base.png keyboard, drawn at KEYBOARD_SCALE so the mouse fits on its right.
        for(int i=0;i<xs.length;i++) keys.add(new Key(codes[i],labels[i],new Rect(Math.round(KEYBOARD_X+(xs[i]-KEYBOARD_SRC.x())*KEYBOARD_SCALE),Math.round(KEYBOARD_Y+(y-KEYBOARD_SRC.y())*KEYBOARD_SCALE),Math.round(ws[i]*KEYBOARD_SCALE),Math.round(h*KEYBOARD_SCALE))));
    }
}
