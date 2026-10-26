package kr.chacademi.chatlayout;

/** Shared visible left edge for message text, input text and the native tab caption. */
public final class ContentInsets {
    public static final int GUI=5, BOTTOM=5;
    /** Message clipping stops inside the body border, never at the extended input frame. */
    public static LayoutMath.Rect messageClip(LayoutMath.Rect body){
        return new LayoutMath.Rect(body.x()+1,body.top(),Math.max(0,body.width()-2),Math.max(0,body.height()-1));
    }
    public static LayoutMath.Rect messageClipWithControls(LayoutMath.Rect body){
        var clip=messageClip(body);
        return new LayoutMath.Rect(clip.x(),clip.top(),Math.max(0,clip.width()-AppearanceControls.RESERVE),clip.height());
    }
    public static final int TAB_BACKGROUND=GUI-2; // ChatPlus 2.8.1 adds two GUI units before a tab name.
    private ContentInsets(){}
    public static int nativePadding(float textScale){
        if(!Float.isFinite(textScale)||textScale<=0)throw new IllegalArgumentException("Text scale must be positive and finite");
        return Math.max(1,Math.round(GUI/textScale));
    }
}
