package school.magiccodex.client;

/**
 * 파티 위젯 배치 계산 (Minecraft 클래스 없음, 테스트 가능).
 * 위젯 좌표는 1920x1080 기준 "디자인 단위"이고, 저장 값은 화면 비율(x,y)과 사용자 배율(scale)이다.
 */
public final class PartyHudLayout {
    public static final float WIDTH=330,HEADER=44,ROW=72,ICON=52,BAR_X=66,HEALTH_Y=27,HEALTH_H=16,MANA_Y=49,MANA_H=13,GRIP=22;
    public static final float MIN_SCALE=.5f,MAX_SCALE=2.5f,DEFAULT_X=.988f,DEFAULT_Y=.50f,DEFAULT_SCALE=.85f;
    public static final int NONE=0,BODY=1,GRIP_HIT=2,HEALTH=3,MANA=4;
    private PartyHudLayout(){}

    public static float height(int members){return HEADER+Math.max(1,members)*ROW;}
    public static float barWidth(){return WIDTH-BAR_X;}
    public static float rowTop(int index){return HEADER+index*ROW;}
    /** 1920x1080 기준 배율 x 사용자 배율. */
    public static float scale(float screenW,float screenH,float userScale){return Math.max(.01f,Math.min(screenW/1920f,screenH/1080f)*clampScale(userScale));}
    public static float fittedScale(float screenW,float screenH,float userScale,int members){
        return Math.min(scale(screenW,screenH,userScale),Math.min(screenW/WIDTH,screenH/height(members)));
    }
    public static float clampScale(float value){return !Float.isFinite(value)?DEFAULT_SCALE:Math.clamp(value,MIN_SCALE,MAX_SCALE);}
    /** 위젯이 화면 밖으로 나가지 않게 왼쪽 위 좌표를 맞춘다 (저장 값은 바꾸지 않음). */
    public static float originX(float fraction,float screenW,float scale){return Math.clamp(fin(fraction,DEFAULT_X)*screenW,0,Math.max(0,screenW-WIDTH*scale));}
    public static float originY(float fraction,float screenH,float scale,int members){return Math.clamp(fin(fraction,DEFAULT_Y)*screenH,0,Math.max(0,screenH-height(members)*scale));}
    public static float defaultOriginX(float screenW,float scale){return Math.max(0,screenW-WIDTH*scale-screenW*.012f);}
    public static float defaultOriginY(float screenH,float scale,int members){return Math.max(0,(screenH-height(members)*scale)/2);}
    private static float fin(float v,float fallback){return Float.isFinite(v)?v:fallback;}

    /** 위젯 안 좌표(디자인 단위)의 무엇을 가리키는지. 행 번호는 {@link #row}. */
    public static int hit(float lx,float ly,int members){
        float h=height(members);
        if(lx<0||ly<0||lx>WIDTH||ly>h)return NONE;
        if(lx>=WIDTH-GRIP&&ly>=h-GRIP)return GRIP_HIT;
        int row=row(ly,members);
        if(row>=0&&lx>=BAR_X){
            float top=rowTop(row);
            if(ly>=top+HEALTH_Y-3&&ly<=top+HEALTH_Y+HEALTH_H+2)return HEALTH;
            if(ly>=top+MANA_Y-2&&ly<=top+MANA_Y+MANA_H+3)return MANA;
        }
        return BODY;
    }
    public static int row(float ly,int members){
        if(ly<HEADER)return -1;int r=(int)((ly-HEADER)/ROW);return r>=0&&r<members?r:-1;
    }
    /** 모서리 잡고 끌기: 왼쪽 위를 고정하고 대각선 거리 비율만큼 키운다. */
    public static float resized(float startScale,float ox,float oy,double startX,double startY,double x,double y){
        double from=(startX-ox)+(startY-oy),to=(x-ox)+(y-oy);
        if(from<=1)return clampScale(startScale);
        return clampScale((float)(startScale*Math.max(.05,to)/from));
    }
    public static float fraction(float value,float max){return !Float.isFinite(value)||!Float.isFinite(max)||max<=0?0:Math.clamp(value/max,0,1);}
    /** 팝업 숫자: 소수점 아래가 0이면 정수로. */
    public static String number(float v){
        float r=Math.round(v*10)/10f;return r==(int)r?Integer.toString((int)r):String.format(java.util.Locale.ROOT,"%.1f",r);
    }
    /** 기숙사 번호(SchoolProtocol.HOUSES 순서) -> 아이콘: 아르케온=나침반 별, 루미나=초승달, 베스티아즈=나뭇가지·잎, 노크세르=태양 (사용자 확정). */
    public static String icon(int house){
        return switch(house){case 0->"house_star";case 1->"house_moon";case 2->"house_leaf";case 3->"house_sun";default->null;};
    }
}
