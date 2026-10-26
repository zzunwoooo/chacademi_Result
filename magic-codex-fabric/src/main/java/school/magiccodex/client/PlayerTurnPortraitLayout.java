package school.magiccodex.client;

import java.util.function.IntBinaryOperator;

/** Shared 1600x900 dialogue coordinates. Only the body below the panel is clipped. */
final class PlayerTurnPortraitLayout {
    static final int TOP=75, PANEL_TOP=639, CLIP_BOTTOM=PANEL_TOP+32;
    private static final float CENTER_X=415, BODY_HEIGHT=660, MAX_WIDTH=700;
    record Bounds(int x,int y,int width,int height) {}
    record Placement(int x,int y,int width) {}

    /** Run once on Portrait-IO, never scan pixels during a frame or GPU upload. */
    static Bounds bounds(int width,int height,IntBinaryOperator alpha) {
        int left=width,top=height,right=-1,bottom=-1;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(alpha.applyAsInt(x,y)>0){
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
        }
        return right<left?new Bounds(0,0,width,height):new Bounds(left,top,right-left+1,bottom-top+1);
    }

    static Placement place(int imageWidth,int imageHeight,Bounds b) {
        float scale=Math.min(BODY_HEIGHT/b.height(),MAX_WIDTH/b.width());
        int width=Math.max(1,Math.round(imageWidth*scale));
        // Use the actual rounded draw dimensions, including aspect-ratio rounding.
        float sx=width/(float)imageWidth;
        float sy=Math.round(width*(float)imageHeight/imageWidth)/(float)imageHeight;
        return new Placement(Math.round(CENTER_X-(b.x()+b.width()/2f)*sx),
                (int)Math.ceil(Math.max(TOP-b.y()*sy, CLIP_BOTTOM-(b.y()+b.height())*sy)),width);
    }

    /** 서버 NPC 초상화 칸 (1024x1536 그림을 65,-5 에 700x1050 으로 그리는 자리). */
    static final int BOX_X=65, BOX_Y=-5, BOX_WIDTH=700, BOX_HEIGHT=1050;

    /** 크기가 제각각인 외부 초상화를 같은 칸에 비율을 지켜 맞춘다. 가로는 가운데, 세로는 바닥에 붙인다. */
    static Placement fit(int imageWidth,int imageHeight) {
        if(imageWidth<=0||imageHeight<=0)return new Placement(BOX_X,BOX_Y,BOX_WIDTH);
        float scale=Math.min(BOX_WIDTH/(float)imageWidth,BOX_HEIGHT/(float)imageHeight);
        int width=Math.max(1,Math.min(BOX_WIDTH,Math.round(imageWidth*scale)));
        // PlayerPortraitTexture.draw 와 같은 반올림으로 실제 높이를 구한다.
        int height=Math.round(width*(float)imageHeight/imageWidth);
        if(height>BOX_HEIGHT){width=Math.max(1,(int)Math.floor(BOX_HEIGHT*(double)imageWidth/imageHeight));height=Math.min(BOX_HEIGHT,Math.round(width*(float)imageHeight/imageWidth));}
        return new Placement(BOX_X+(BOX_WIDTH-width)/2,BOX_Y+BOX_HEIGHT-height,width);
    }
}
