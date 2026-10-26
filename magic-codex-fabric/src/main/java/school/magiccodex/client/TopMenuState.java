package school.magiccodex.client;

/** Layout and animation share one coordinate system, including all hit targets. */
public final class TopMenuState {
    public static final String[] LABELS={"마일리지샵","내 정보","소셜","이동","도감","PVP","퀘스트","우편함","설정"};
    private static final String[] COMMANDS={"마일리지상점","","","지도","","큐디치","의뢰","우편함",""};
    /** Every item is live; 이동 (3) opens the 지도 이동 window (2026-10-24), 0 = 마일리지 상점. */
    public static boolean enabled(int index){return true;}
    public static final float STEP=38,HEIGHT=36;
    public static final float SEASON_SPACE=28,SEASON_CENTER=28;
    public static final float TEMPERATURE_SPACE=56;
    public static final float DROPDOWN_WIDTH=76, DROPDOWN_TOP=40, ROW_HEIGHT=64, DROPDOWN_PAD=9;
    private static final String[][] CHILDREN={{},{"스탯","장비","칭호"},{"친구","파티","기숙사"},{},{"마법 도감","펫 도감","아티팩트 도감","물고기 도감"},{},{},{},{}};
    private int group=-1;
    private long groupStarted;
    private float groupProgress;
    public static boolean isGroup(int index){return index==1||index==2||index==4;}
    public int group(){return group;}
    public float groupProgress(){return groupProgress;}
    public static String childLabel(int group,int row){return CHILDREN[group][row];}
    public static int childHit(int group,int row){return 100+group*10+row;}
    public static float rowTop(int row){return DROPDOWN_TOP+DROPDOWN_PAD+ROW_HEIGHT*row;}
    public static int rows(int group){return group<0||group>=CHILDREN.length?0:CHILDREN[group].length;}
    public static float dropdownHeight(){return dropdownHeight(1);}
    /** 도감 has four rows since 물고기 도감 (2026-10-20); the others three. */
    public static float dropdownHeight(int group){return DROPDOWN_PAD*2+ROW_HEIGHT*Math.max(1,rows(group));}
    public float dropdownLeft(float base){return iconCenter(base,group)-DROPDOWN_WIDTH/2;}
    public void closeGroup(){group=-1;groupProgress=0;}
    public void toggleGroup(int index,long now){
        if(!isGroup(index)||!expanded||progress<.999f)return;
        if(group==index){closeGroup();return;}
        group=index;groupStarted=now;groupProgress=0;
    }
    public boolean insideDropdown(float x,float y,float base){
        return group>=0&&x>=dropdownLeft(base)&&x<=dropdownLeft(base)+DROPDOWN_WIDTH
            &&y>=DROPDOWN_TOP&&y<=DROPDOWN_TOP+dropdownHeight(group);
    }
    private HudSeason season=HudSeason.SPRING;
    private boolean expanded;
    private float from,progress;
    private long started;
    public float progress(){return progress;}
    public boolean expanded(){return expanded;}
    public HudSeason season(){return season;}
    public void season(HudSeason value){season=java.util.Objects.requireNonNull(value);}
    public static float baseWidth(float moneyWidth){return 153+SEASON_SPACE+TEMPERATURE_SPACE+Math.clamp(moneyWidth,45,138);}
    public static boolean seasonHovered(float x,float y){return Math.abs(x-SEASON_CENTER)<=12 && y>=5 && y<=31;}
    public void update(long now){
        float t=Math.clamp((now-started)/240f,0,1);
        float eased=t*t*(3-2*t);
        progress=from+((expanded?1:0)-from)*eased;
        if(group>=0){float g=Math.clamp((now-groupStarted)/180f,0,1);groupProgress=g*g*(3-2*g);}
    }
    public void toggle(long now){closeGroup();update(now);from=progress;expanded=!expanded;started=now;}
    public float width(float base){return base+STEP*LABELS.length*progress;}
    public float iconCenter(float base,int index){return base-13+STEP*index;}
    public float arrowCenter(float base){return width(base)-20;}
    /** -2 outside, -1 arrow, 0..6 menu, 100+group*10+row submenu. Hidden/transitioning items cannot activate. */
    public int hit(float x,float y,float base){
        if(group>=0&&expanded&&progress>=.999f&&groupProgress>=.999f&&insideDropdown(x,y,base)){
            int row=(int)((y-DROPDOWN_TOP-DROPDOWN_PAD)/ROW_HEIGHT);
            if(y>=DROPDOWN_TOP+DROPDOWN_PAD&&row>=0&&row<rows(group))return childHit(group,row);
            return -2;
        }
        if(y<5 || y>31)return -2;
        if(Math.abs(x-arrowCenter(base))<=13)return -1;
        if(progress<.999f || !expanded)return -2;
        for(int i=0;i<LABELS.length;i++)if(Math.abs(x-iconCenter(base,i))<=16)return i;
        return -2;
    }
    public static String command(int index){return COMMANDS[index];}
}
