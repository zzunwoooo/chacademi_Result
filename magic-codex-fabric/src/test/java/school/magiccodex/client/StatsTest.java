package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatsTest {
    @Test void socialActionUsesExplicitHookAndNeverPretendsToSucceed(){
        var id=java.util.UUID.randomUUID();StatsSocialActions.reset();
        assertTrue(StatsSocialActions.activate(StatsSocialActions.Action.FRIEND,id).contains("아직 연결"));
        assertTrue(StatsSocialActions.activate(StatsSocialActions.Action.POPULARITY,id).contains("아직 연결"));
        try{
            StatsSocialActions.connect((action,target)->{assertEquals(id,target);return action.name();});
            assertEquals("POPULARITY",StatsSocialActions.activate(StatsSocialActions.Action.POPULARITY,id));
        }finally{StatsSocialActions.reset();}
    }
    @Test void unavailableValuesNeverBecomeZeroOrNan(){
        assertEquals("—",StatsValues.number(null));
        assertEquals("—",StatsValues.number(Double.NaN));
        assertEquals("—",StatsValues.number(Double.POSITIVE_INFINITY));
        assertEquals("100",StatsValues.number(100.0));
        assertEquals("5.3",StatsValues.number(5.25));
        assertEquals("-3",StatsValues.number(-3.0));
        assertEquals(0,StatsValues.fraction(null,100.0));
        assertEquals(0,StatsValues.fraction(50.0,0.0));
        assertEquals(0,StatsValues.fraction(Double.NaN,100.0));
        assertEquals(1,StatsValues.fraction(120.0,100.0));
        assertEquals(.5f,StatsValues.fraction(50.0,100.0));
    }
    /** 정보창 v4: min(가로비, 세로비) 균일 배율 + 가운데 정렬, 모든 클릭 영역이 캔버스 안에서 왕복된다. */
    @Test void layoutScalesUniformlyCentredAndKeepsTargets(){
        for(int[] size:new int[][]{{320,180},{640,360},{854,480},{1920,1080},{800,1000},{3440,1440},{1672,941}}){
            var l=StatsLayout.fit(size[0],size[1]);
            assertEquals(Math.min(size[0]/1672f,size[1]/941f),l.scale(),1e-6);
            assertEquals(size[0]-(l.x()*2+1672*l.scale()),0,1e-3);
            assertEquals(size[1]-(l.y()*2+941*l.scale()),0,1e-3);
            var boxes=new java.util.ArrayList<StatsLayout.Box>(java.util.List.of(StatsLayout.CLOSE,StatsLayout.TITLE_LINK,StatsLayout.EQUIPMENT_LINK));
            boxes.addAll(java.util.List.of(StatsLayout.BOTTOM_BUTTONS));boxes.addAll(java.util.List.of(StatsLayout.POPULARITY_BUTTONS));
            for(var r:boxes){
                double x=l.x()+r.centerX()*l.scale(),y=l.y()+r.centerY()*l.scale();
                assertTrue(r.contains(l.localX(x),l.localY(y)));
                assertTrue(r.x()>=0&&r.y()>=0&&r.right()<=1672&&r.bottom()<=941);
            }
        }
        assertThrows(IllegalArgumentException.class,()->StatsLayout.fit(0,10));
        // 클릭 영역이 서로 겹치지 않는다
        var all=new java.util.ArrayList<StatsLayout.Box>(java.util.List.of(StatsLayout.CLOSE,StatsLayout.TITLE_LINK,StatsLayout.EQUIPMENT_LINK));
        all.addAll(java.util.List.of(StatsLayout.BOTTOM_BUTTONS));all.addAll(java.util.List.of(StatsLayout.POPULARITY_BUTTONS));
        for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++){var a=all.get(i);var b=all.get(j);
            assertFalse(a.x()<b.right()&&b.x()<a.right()&&a.y()<b.bottom()&&b.y()<a.bottom(),a+" / "+b);}
    }
    /** layout-metrics.json (오너 납품) 좌표와 코드 좌표가 같다. */
    @Test void layoutMatchesDeliveredMetrics()throws Exception{
        var file=java.nio.file.Path.of("../docs/design/profile-ui-v4/docs/layout-metrics.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(file),"납품 패키지 없음");
        String json=java.nio.file.Files.readString(file);
        assertBox(json,"outer",StatsLayout.OUTER);assertBox(json,"header",StatsLayout.HEADER);
        assertBox(json,"left",StatsLayout.LEFT);assertBox(json,"center",StatsLayout.CENTER);assertBox(json,"right",StatsLayout.RIGHT);
        assertBox(json,"portrait",StatsLayout.PORTRAIT);assertBox(json,"identity",StatsLayout.IDENTITY);
        assertBox(json,"pvp",StatsLayout.PVP);assertBox(json,"quidditch",StatsLayout.QUIDDITCH);
        assertBox(json,"adventure",StatsLayout.ADVENTURE);assertBox(json,"equipment",StatsLayout.EQUIPMENT);
        assertBoxes(json,"tierIcons",StatsLayout.TIER_ICONS);assertBoxes(json,"armorSlots",StatsLayout.ARMOR_SLOTS);
        assertBoxes(json,"accessorySlots",StatsLayout.ACCESSORY_SLOTS);assertBoxes(json,"bottomButtons",StatsLayout.BOTTOM_BUTTONS);
        assertBoxes(json,"popularityButtons",StatsLayout.POPULARITY_BUTTONS);
        assertBox(json,"bounds",StatsLayout.CLOSE_ICON);
        assertArrayEquals(numbers(json,"statRowY"),java.util.Arrays.stream(StatsLayout.STAT_ROW_Y).toArray());
        assertEquals(1304,number(json,"\"x\""));assertEquals(StatsLayout.STAT_ICON_SIZE,number(json,"\"size\""));
        assertEquals(StatsLayout.STAT_LABEL_X,number(json,"\"statLabelX\""));assertEquals(StatsLayout.STAT_VALUE_RIGHT,number(json,"\"statValueRight\""));
        assertEquals(1672,number(json,"\"width\""));assertEquals(941,number(json,"\"height\""));
        assertEquals(9,StatsLayout.ARMOR_SLOTS.length+StatsLayout.ACCESSORY_SLOTS.length); // 주무기·보조손 없음
    }
    private static int[] numbers(String json,String key){
        var m=java.util.regex.Pattern.compile("\""+key+"\"\\s*:\\s*\\[([^\\[\\]]*)\\]").matcher(json);assertTrue(m.find(),key);
        return java.util.Arrays.stream(m.group(1).split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray();
    }
    private static int number(String json,String quotedKey){
        var m=java.util.regex.Pattern.compile(quotedKey+"\\s*:\\s*(-?\\d+)").matcher(json);assertTrue(m.find(),quotedKey);return Integer.parseInt(m.group(1));
    }
    private static void assertBox(String json,String key,StatsLayout.Box b){assertArrayEquals(numbers(json,key),new int[]{b.x(),b.y(),b.w(),b.h()},key);}
    private static void assertBoxes(String json,String key,StatsLayout.Box[] boxes){
        var m=java.util.regex.Pattern.compile("\""+key+"\"\\s*:\\s*\\[(\\s*\\[[^\\]]*\\]\\s*,?)+\\s*\\]").matcher(json);assertTrue(m.find(),key);
        var inner=java.util.regex.Pattern.compile("\\[([^\\[\\]]*)\\]").matcher(m.group());int i=0;
        while(inner.find()){var v=java.util.Arrays.stream(inner.group(1).split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray();var b=boxes[i++];assertArrayEquals(v,new int[]{b.x(),b.y(),b.w(),b.h()},key+i);}
        assertEquals(boxes.length,i,key);
    }
    @Test void derivedValuesNeverInventNumbers(){
        assertEquals(19.35,StatsLayout.hasteReduction(24.0),.01); // 레퍼런스 24 → 약 19.4%
        assertEquals(0,StatsLayout.hasteReduction(0.0));
        assertNull(StatsLayout.hasteReduction(null));assertNull(StatsLayout.hasteReduction(-1.0));
        assertNull(StatsLayout.progress(0,0));assertNull(StatsLayout.progress(null,240));assertNull(StatsLayout.progress(3,null));
        assertEquals(86/240f,StatsLayout.progress(86,240),1e-6);assertEquals(1f,StatsLayout.progress(5,2));
        assertEquals("랭킹 12위",StatsLayout.rank(12));assertEquals("미집계",StatsLayout.rank(0));assertEquals("미집계",StatsLayout.rank(-3));
        assertEquals("1,624",StatsLayout.thousands(1624));
    }
    @Test void exactClassCountBoundedMotionAndFrontBackTransitions(){
        var s=new StatsStars();boolean front=false,back=false;
        for(int rank=1;rank<=9;rank++)for(int step=0;step<600;step++){
            s.update(rank,step*.2);assertEquals(rank,s.count());
            for(int i=0;i<s.count();i++){
                assertTrue(Math.abs(s.x(i))<=1);assertTrue(Math.abs(s.y(i))<=1);
                assertTrue(Math.abs(s.depth(i))<=1);
                front|=s.foreground(i);back|=!s.foreground(i);
            }
        }
        assertTrue(front&&back);
        s.update(5,9);float x=s.x(0),y=s.y(0);
        s.update(5,9.001);assertTrue(Math.abs(x-s.x(0))<.002&&Math.abs(y-s.y(0))<.002);
    }
}
