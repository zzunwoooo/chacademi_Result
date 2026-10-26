package school.magiccodex.client;

/**
 * 정보창 v4 배치 (오너 납품 docs/design/profile-ui-v4, 2026-10-26). 모든 좌표는 1672×941 기준 캔버스 [x,y,w,h].
 * 화면에는 min(가로비, 세로비) 하나로 균일하게 줄이고 가운데 정렬한다 (layout-metrics.json coordinateRule).
 * layout-metrics.json 에 있는 값은 그대로 옮겼고 (StatsTest 가 파일과 비교), 나머지(글자 기준선·구분선)는 승인 레퍼런스(approved-v4.png)와
 * 실제 PNG 조립 미리보기에서 잰 값이다. 순수 계산만 — Minecraft 없음.
 */
public record StatsLayout(float x, float y, float scale) {
    public static final int WIDTH=1672, HEIGHT=941;
    public record Box(int x,int y,int w,int h){
        public boolean contains(double px,double py){return px>=x&&py>=y&&px<x+w&&py<y+h;}
        public float centerX(){return x+w/2f;}
        public float centerY(){return y+h/2f;}
        public int right(){return x+w;}
        public int bottom(){return y+h;}
    }
    // ---- layout-metrics.json
    public static final Box OUTER=new Box(36,53,1601,841), HEADER=new Box(36,53,1601,65);
    public static final Box LEFT=new Box(48,128,494,753), CENTER=new Box(564,128,692,753), RIGHT=new Box(1298,145,311,645);
    public static final Box PORTRAIT=new Box(48,128,310,588), IDENTITY=new Box(368,210,160,300);
    public static final Box PVP=new Box(564,128,340,254), QUIDDITCH=new Box(916,128,340,254);
    public static final Box[] TIER_ICONS={new Box(573,208,170,160),new Box(925,208,170,160)};
    public static final Box ADVENTURE=new Box(564,395,692,218), EQUIPMENT=new Box(564,625,692,256);
    /** 머리·상의·하의·신발 */
    public static final Box[] ARMOR_SLOTS={new Box(574,699,143,77),new Box(747,699,143,77),new Box(921,699,143,77),new Box(1095,699,145,77)};
    /** 아티팩트·반지·귀걸이·목걸이·빗자루 (주무기·보조손 없음) */
    public static final Box[] ACCESSORY_SLOTS={new Box(574,804,100,76),new Box(697,804,102,76),new Box(817,804,103,76),new Box(947,804,105,76),new Box(1078,804,168,76)};
    /** 왼쪽 아래 두 버튼: 본인 = 내 방 가기 / 친구 목록 · 상대 = 상대 방 가기 / 친구 추가 */
    public static final Box[] BOTTOM_BUTTONS={new Box(58,807,228,65),new Box(306,807,218,65)};
    /** 인기도 올리기 / 내리기 */
    public static final Box[] POPULARITY_BUTTONS={new Box(220,738,36,38),new Box(265,738,36,38)};
    /** 능력치 행 가운데 y: 지능 · 최대 체력 · 마나 · 마나 회복 · 마법 가속 · 방어력 */
    public static final int[] STAT_ROW_Y={249,345,441,537,633,729};
    public static final int STAT_ICON_X=1304, STAT_ICON_SIZE=40, STAT_LABEL_X=1374, STAT_VALUE_RIGHT=1602;
    public static final Box CLOSE_ICON=new Box(1584,75,24,24);
    /** 닫기 클릭 영역: 그림(24×24)보다 넓게 (버튼 사각형 전체가 클릭 영역). */
    public static final Box CLOSE=new Box(1572,63,48,48);
    public static final float FONT_TITLE=40, FONT_NAME=50, FONT_SECTION=27, FONT_BODY=23, FONT_SMALL=19, FONT_FOOTNOTE=15;
    // ---- 레퍼런스에서 잰 값
    /** 제목 "정보창" 왼쪽·가운데 y, 제목 앞 금색 세로 막대 (x, y1, y2). */
    public static final int TITLE_X=103, TITLE_Y=85, TITLE_BAR_X=73, TITLE_BAR_Y1=70, TITLE_BAR_Y2=103;
    /** 이름·칭호·클래스·기숙사 (왼쪽 x, 가운데 y). 아이콘 가운데 x·크기. */
    public static final int NAME_Y=243, TITLE_LINE_Y=306, CLASS_Y=356, HOUSE_Y=404, ROW_ICON_X=386, ROW_ICON_SIZE=32, ROW_TEXT_X=416;
    public static final int IDENTITY_DIVIDER_Y=450;
    /** "칭호 자세히 >" 클릭 영역. */
    public static final Box TITLE_LINK=new Box(362,466,170,32);
    /** 인기도 줄 (가운데 y 757): 별 · "인기도 n" · 버튼 · 세로 구분선 · 하우징 아이콘 · "하우징" · 하트 · 합계 */
    public static final int POP_Y=757, POP_STAR_X=70, POP_TEXT_X=96, POP_DIVIDER_X=323, HOUSING_ICON_X=346, HOUSING_TEXT_X=372, LIKE_ICON_X=452, LIKE_TEXT_X=470;
    public static final int LEFT_DIVIDER_1=716, LEFT_DIVIDER_2=790;
    /** 머리글 x (오너 2026-10-26: 머리글 아이콘 PNG 가 없어 "모험 기록·장착 장비·능력치·카드 제목" 을 칸 왼쪽 끝으로 붙임). 카드 안: 머리글 x·y, 머리글 아래 구분선 y, 경지 이름·레이팅 라벨·레이팅·랭킹 (카드 왼쪽에서의 x, y). */
    public static final int CARD_TITLE_DX=20, CARD_TITLE_Y=156, CARD_HEADER_LINE_Y=186, CARD_TEXT_DX=191, CARD_TIER_Y=228, CARD_RATING_LABEL_Y=266, CARD_RATING_Y=303, CARD_RANK_LINE_Y=337, CARD_RANK_Y=358;
    /** 모험 기록: 머리글, 발견한 마법 행(막대), 진행 중 퀘스트 행, 추적 퀘스트 칸(06_quest_row). */
    public static final int SECTION_TEXT_X=580, ADVENTURE_TITLE_Y=417, SPELL_ROW_Y=471, QUEST_ROW_Y=518, ADVENTURE_VALUE_RIGHT=1240;
    public static final Box SPELL_BAR=new Box(764,465,360,13), QUEST_PANEL=new Box(564,543,692,70), TRACK_BAR=new Box(925,584,305,13);
    public static final int TRACK_LABEL_Y=566, TRACK_NAME_Y=592, TRACK_TEXT_X=584, TRACK_VALUE_RIGHT=1229;
    /** 장착 장비: 머리글 y, "장비 자세히 >" 클릭 영역, 칸 이름 y (윗줄·아랫줄). */
    public static final int EQUIPMENT_TITLE_Y=645, ARMOR_LABEL_Y=683, ACCESSORY_LABEL_Y=790;
    public static final Box EQUIPMENT_LINK=new Box(1120,632,130,32);
    /** 능력치 머리글, 마법 가속 행의 이름·아랫줄(재사용 대기시간 감소) y. */
    public static final int STATS_TITLE_X=1306, STATS_TITLE_Y=161, HASTE_LABEL_Y=627, HASTE_NOTE_Y=663;
    /** 구분선 (실제 PNG 조립 미리보기): 머리 아래, 세로 기둥 두 개, 오른쪽 행 사이, 가운데 섹션 머리글 위아래. */
    public static final int HEADER_LINE_Y=118, COLUMN_LINE_1=542, COLUMN_LINE_2=1278;
    public static final int[] RIGHT_LINES={197,298,394,490,586,682,778};
    public static final int[] CENTER_LINES={394,442,624,667};

    public static StatsLayout fit(int width,int height) {
        if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid viewport");
        float s=Math.min(width/(float)WIDTH,height/(float)HEIGHT);
        return new StatsLayout((width-WIDTH*s)/2,(height-HEIGHT*s)/2,s);
    }
    public double localX(double value){return (value-x)/scale;}
    public double localY(double value){return (value-y)/scale;}
    /** 마법 가속 h 의 재사용 대기시간 감소 % = 100·h/(100+h). h 가 없거나 음수면 null. */
    public static Double hasteReduction(Double haste){
        if(haste==null||!Double.isFinite(haste)||haste<0)return null;
        return 100.0*haste/(100.0+haste);
    }
    /** 진행 막대 채움 0~1. 분모가 0·모름이면 null (막대를 비운다, 0 으로 꾸미지 않음). */
    public static Float progress(Integer current,Integer total){
        if(current==null||total==null||total<=0)return null;
        return (float)Math.clamp(current/(double)total,0,1);
    }
    /** 랭킹 문구: 1 이상이면 "랭킹 N위", 그 외 "미집계". */
    public static String rank(int rank){return rank>0?"랭킹 "+rank+"위":"미집계";}
    /** 큰 수 천 단위 쉼표 (레이팅 1,624). */
    public static String thousands(long value){return String.format(java.util.Locale.ROOT,"%,d",value);}
}
