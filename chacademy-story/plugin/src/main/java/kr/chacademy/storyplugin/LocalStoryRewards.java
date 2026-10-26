package kr.chacademy.storyplugin;
/** Fixed self-only effects for the current client story. No commands, targets or amounts from the network. */
final class LocalStoryRewards {
    record Effect(String affinityNpc,int affinityDelta,String discoveryEvent,double discoveryAmount) {}
    /** 지금 있는 보상 키 전부 ("대화.노드"). 노드 end = 끝 명령, 결말 장면 이름 = 그 결말의 명령, 그 밖 = 대화 중 이벤트. */
    static final java.util.List<String> KEYS=java.util.List.of("ch1-2.e_s11","ch1-3.end");
    /** 영수증 상태 (progress.yml rewards). pending = 저장 뒤 효과 적용 전·적용 중 (적용 여부 불확실 — 다시 주지 않음). */
    static final String PENDING="pending",APPLIED="applied",FAILED="failed";
    static Effect resolve(String key) {
        if(key==null)return null;
        return switch(key) {
            case "ch1-2.e_s11" -> new Effect("teacher_ellna",-1,null,0);
            case "ch1-3.end" -> new Effect(null,0,"jump.sprinting",30);
            default -> null;
        };
    }
    static String dialogue(String key){int dot=key==null?-1:key.indexOf('.');return dot<=0?"":key.substring(0,dot);}
    static String node(String key){int dot=key==null?-1:key.indexOf('.');return dot<0?"":key.substring(dot+1);}
    /** 이 대화에 보상 키가 있는지 (있으면 서버가 끝 명령이 없어도 세션을 연다 — 보상을 세션에 묶기 위해). */
    static boolean hasRewards(String dialogueId){for(String k:KEYS)if(dialogue(k).equals(dialogueId))return true;return false;}
    static boolean validState(Object s){return PENDING.equals(s)||APPLIED.equals(s)||FAILED.equals(s);}
    /**
     * 보상 패킷이 서버 세션에 묶여 있는지. liveId/liveReady = 지금 열린 서버 세션 (없으면 null/false),
     * finishedId/finishedEnding = 방금 서버가 확인한 완료 (결말 검증까지 통과한 것, 없으면 null),
     * graphEvents = 서버 흐름이 있으면 그 대화의 이벤트 이름들 (없으면 null = 확인 생략).
     * <ul><li>end: 그 대화의 확인된 완료가 있어야 한다</li>
     * <li>결말 장면 이름: 확인된 완료의 결말이 그 장면이어야 한다</li>
     * <li>이벤트: 그 대화의 세션이 열려 있고 준비(ready)된 상태여야 한다 (서버 흐름이 있으면 그 흐름에 있는 이벤트여야 한다)</li></ul>
     */
    static boolean bound(String key,String liveId,boolean liveReady,String finishedId,String finishedEnding,java.util.Set<String> graphEvents){
        if(resolve(key)==null)return false;
        String d=dialogue(key),node=node(key);
        boolean finished=d.equals(finishedId);
        if(node.equals("end"))return finished;
        if(finished&&node.equals(finishedEnding))return true;
        if(!liveReady||!d.equals(liveId))return false;
        return graphEvents==null||graphEvents.contains(node);
    }
}
