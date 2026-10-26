package school.magiccodex.paper;

import java.util.*;

/** Pure parsing and limits shared by Korean administrator commands and their tests. */
final class AdminCommandRules {
    /** /유저관리 &lt;닉네임&gt; 스탯 의 항목. */
    static final List<String> STATS=List.of("현재마나","최대마나","마나회복","마법가속");
    static final List<String> CHANGES=List.of("설정","추가","빼기");
    private AdminCommandRules(){}
    static double nonnegative(String value,double maximum){
        final double parsed;
        try{parsed=Double.parseDouble(value);}catch(NumberFormatException e){throw new IllegalArgumentException("숫자를 입력해 주세요.");}
        return nonnegative(parsed,maximum);
    }
    static double nonnegative(double parsed,double maximum){
        if(!Double.isFinite(parsed)||parsed<0||parsed>maximum)throw new IllegalArgumentException("수치 범위는 0~"+number(maximum)+"입니다.");
        return parsed;
    }
    /** 설정·추가·빼기 (예전 이름 감소도 받는다: 다른 모듈이 아직 쓸 수 있음). */
    static double changed(double previous,String action,double value,double maximum){
        if(!Double.isFinite(previous)||!Double.isFinite(value)||previous<0||value<0||value>maximum)throw new IllegalArgumentException("수치 범위를 확인해 주세요.");
        double next=switch(action){case "설정"->value;case "추가"->previous+value;case "빼기","감소"->Math.max(0,previous-value);default->throw new IllegalArgumentException("설정, 추가, 빼기 중 선택해 주세요.");};
        if(!Double.isFinite(next)||next>maximum)throw new IllegalArgumentException("바꾼 뒤 수치가 최대 "+number(maximum)+"을 넘어요.");
        return next;
    }
    /** 기숙사 점수 (SchoolBridge가 쓴다). 보기·설정·추가·빼기, 예전 이름 조회·감소도 받는다. */
    static long scoreValue(String action,long amount){
        boolean subtract=action.equals("빼기")||action.equals("감소");
        if(amount < -SchoolStore.MAX_SCORE||amount>SchoolStore.MAX_SCORE||(!action.equals("설정")&&amount<0))throw new IllegalArgumentException("점수 범위를 확인해 주세요. 추가·빼기는 0 이상의 정수입니다.");
        if(subtract)return -amount;
        return switch(action){case "보기","조회","설정","추가"->amount;default->throw new IllegalArgumentException("보기, 설정, 추가, 빼기 중 선택해 주세요.");};
    }
    /** 코어 성향 이름 → 번호. 스탯 이름이 마력→지능으로 바뀌었으므로(2026-10-25) 예전 이름 "마력"도 받는다. */
    static int affinity(String value){return switch(value){case "지능","마력"->0;case "마나"->1;case "마법가속"->2;default->throw new IllegalArgumentException("성향은 지능, 마나, 마법가속 중 선택해 주세요.");};}
    static void validateCore(int nodes,double success,double bonus,double gain,int affinity){
        if(nodes<1||nodes>10)throw new IllegalArgumentException("코어 수는 1~10입니다.");
        for(double percent:new double[]{success,bonus})if(!Double.isFinite(percent)||percent<0||percent>100)throw new IllegalArgumentException("성공률과 별잡기 보너스는 0~100%입니다.");
        if(!Double.isFinite(gain)||gain<=0||gain>10000)throw new IllegalArgumentException("능력치 상승 배율은 0 초과~10000입니다.");
        if(affinity<0||affinity>2)throw new IllegalArgumentException("등록된 코어 성향을 선택해 주세요.");
    }
    static boolean titleAllowed(TitleDefinition title,boolean remove){return title!=null&&(remove||title.enabled());}
    /** 명령 칸은 공백 전까지라 이름의 띄어쓰기를 지운 모양으로 받는다. */
    static String compact(String value){return value==null?"":value.replace(" ","");}
    /** An exact ID wins; then a display name (with or without spaces). A duplicate display name is always rejected. */
    static String resolve(Map<String,String> catalog,String input){
        if(input==null)return null;
        String key=input.strip();if(key.isEmpty())return null;if(catalog.containsKey(key))return key;
        String id=null;for(var entry:catalog.entrySet())if(entry.getValue().equalsIgnoreCase(key)){if(id!=null)return null;id=entry.getKey();}
        if(id!=null)return id;
        String wanted=compact(key);
        for(var entry:catalog.entrySet())if(compact(entry.getValue()).equalsIgnoreCase(wanted)){if(id!=null)return null;id=entry.getKey();}
        return id;
    }
    /** 자동완성 후보: 이름(띄어쓰기 없앤 모양)이 하나로 정해지면 이름, 겹치면 ID. 한글 이름이 먼저 온다. */
    static List<String> catalogChoices(Map<String,String> catalog){
        var names=new TreeSet<String>();var ids=new TreeSet<String>();
        for(var entry:catalog.entrySet()){String shown=compact(entry.getValue());if(!shown.isEmpty()&&entry.getKey().equals(resolve(catalog,shown)))names.add(shown);else ids.add(entry.getKey());}
        var out=new ArrayList<String>(names);out.addAll(ids);return List.copyOf(out);
    }
    static List<String> filter(Collection<String> choices,String prefix){return choices.stream().filter(Objects::nonNull).filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).distinct().sorted().limit(256).toList();}
    static String number(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
}
