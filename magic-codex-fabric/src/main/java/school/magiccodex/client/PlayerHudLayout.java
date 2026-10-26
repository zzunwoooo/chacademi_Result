package school.magiccodex.client;

public record PlayerHudLayout(float scale,float width,float height,float dockLift,float dockShift) {
    public static final float SIZE = 1.35f;
    /** Right edge of the portrait/vitals block (vitalX + VITAL_WIDTH) plus a small margin, in of() units. */
    static final float PORTRAIT_RIGHT = 378;
    /** Top of the portrait frame above the screen bottom (frameY offset 100 + frame radius 90), in of() units. */
    static final float PORTRAIT_TOP = 190;
    /** Right edge of the round frame (frameX + radius + margin) and top of the health/mana gauges above the bottom, in of() units. */
    static final float FRAME_RIGHT = 194, VITALS_TOP = 120;
    public PlayerHudLayout(float scale,float width,float height){this(scale,width,height,0,0);}
    /**
     * Hotbar dock only (hotbar, hunger/armour chips, held item name). The HUD size setting never moves or scales
     * the portrait/vitals: those always use of(). When the enlarged dock would overlap the portrait it moves right
     * (up to the skill bar) and only then up; 50% keeps the original coordinates exactly.
     */
    public static PlayerHudLayout player(int width,int height,int percent){
        var base=of(width,height);percent=Math.clamp(percent,0,100);
        if(percent==0||percent==50)return base;
        float scale=base.scale()*percent/50f,w=width/scale,h=height/scale;
        float skillScale=Math.min(width/1672f,height/941f),lift=0,shift=0;
        if(percent>50){
            float portraitRight=PORTRAIT_RIGHT*base.scale(),skillLeft=width-402*skillScale,left=(w/2-238)*scale;
            if(left<portraitRight)shift=Math.min(portraitRight-left,Math.max(0,skillLeft-(w/2+238)*scale))/scale;
            if((w/2+238+shift)*scale>skillLeft)lift=Math.max(0,(76*skillScale-8*scale+8*base.scale())/scale);
            // Still overlapping: lift the dock just above the gauges, or above the whole frame when it reaches the frame.
            float dockLeft=(w/2-238+shift)*scale;
            if(dockLeft<portraitRight)lift=Math.max(lift,(dockLeft<FRAME_RIGHT*base.scale()?PORTRAIT_TOP:VITALS_TOP)*base.scale()/scale-8);
            // Keep the chips above the dock on screen.
            lift=Math.min(lift,Math.max(0,h-68-39));
        }
        return new PlayerHudLayout(scale,w,h,lift,shift);
    }

    public static PlayerHudLayout of(int width,int height){
        // Keep the corner HUD and inventory dock readable independently of Minecraft GUI scale.
        float scale=Math.max(.01f,SIZE*Math.min(width/1672f,height/941f));
        return new PlayerHudLayout(scale,width/scale,height/scale);
    }
    public static final int CELL = 44;
    public float hotbarLeft(){return width/2-238+dockShift;}
    public float hotbarTop(){return height-68-dockLift;}
    public static final int FRAME_SIZE = 180;
    public static final float STAR_RADIUS = 80;
    public static final int VITAL_WIDTH = 190;
    public float frameX(){return 96;}
    public float frameY(){return height-100;}
    public float vitalX(){return 180;}
    public float healthY(){return height-91;}
    public float manaY(){return height-45;}
    public float slotX(int slot){return hotbarLeft()+56+slot*47;}
    public static float fraction(float value,float max){
        return !Float.isFinite(value)||!Float.isFinite(max)||max<=0?0:Math.clamp(value/max,0,1);
    }
}
