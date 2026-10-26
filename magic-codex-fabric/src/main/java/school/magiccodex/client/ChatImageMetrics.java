package school.magiccodex.client;
/** GUI units; the same transform is applied to the reserved advance, image and chat text. */
public final class ChatImageMetrics {
 private ChatImageMetrics(){}
 public static final float SIZE=12, ADVANCE=13, TOP=-2, BOTTOM=10;
 public static boolean isIcon(int cp){return cp>=0xE110&&cp<=0xE115;}
 public static int untinted(int argb){return (argb&0xFF000000)|0xFFFFFF;}
}
