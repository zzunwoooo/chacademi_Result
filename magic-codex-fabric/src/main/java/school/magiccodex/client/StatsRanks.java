package school.magiccodex.client;

import net.minecraft.util.Util;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * 정보창 1:1 대전 · 큐디치 카드 값. 기존 결투장 전적 요청(KyudichiQueueProtocol PROFILE, 프록시 랭킹 DB)을 그대로 쓴다.
 * 정보창이 요청한 답만 가로채 결투장 창의 전적 탭 값은 건드리지 않는다. 클라이언트 스레드.
 */
final class StatsRanks {
    /** 모드별 (0 큐디치 · 1 1:1 대전): 기다리는 질의 · 받은 전적 (null = 기록 없음) · 받았는지. */
    private static final String[] asked=new String[KyudichiQueueProtocol.MODES];
    private static final KyudichiQueueProtocol.Profile[] data=new KyudichiQueueProtocol.Profile[KyudichiQueueProtocol.MODES];
    private static final boolean[] answered=new boolean[KyudichiQueueProtocol.MODES];
    private static String query;
    private static long askedAt;
    private StatsRanks(){}

    /** 정보창을 열 때 한 번: 본인은 "" (나), 상대는 닉네임. 같은 사람을 30초 안에 다시 열면 받은 값을 쓴다. */
    static void request(String nick){
        String q=nick==null?"":nick.strip();long now=Util.getMeasuringTimeMs();
        if(q.equals(query)&&now-askedAt<30_000)return;
        query=q;askedAt=now;
        for(int mode=0;mode<KyudichiQueueProtocol.MODES;mode++){asked[mode]=q;data[mode]=null;answered[mode]=false;KyudichiQueueClient.send(new KyudichiQueueProtocol.ProfileRequest(mode,q));}
    }
    /** KyudichiQueueClient.receive: 정보창이 물은 답이면 가져가고 true. */
    static boolean take(KyudichiQueueProtocol.ProfileReply reply){
        int mode=reply.mode();if(mode<0||mode>=KyudichiQueueProtocol.MODES||asked[mode]==null||!asked[mode].equals(reply.query()))return false;
        asked[mode]=null;data[mode]=reply.profile();answered[mode]=true;return true;
    }
    static boolean answered(int mode){return answered[mode];}
    static KyudichiQueueProtocol.Profile get(int mode){return data[mode];}
    static void reset(){query=null;askedAt=0;for(int i=0;i<KyudichiQueueProtocol.MODES;i++){asked[i]=null;data[i]=null;answered[i]=false;}}
}
