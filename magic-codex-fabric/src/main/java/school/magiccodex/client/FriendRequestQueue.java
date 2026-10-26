package school.magiccodex.client;

import java.util.*;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.protocol.SocialProtocol.Entry;

/** 받은 친구 신청 알림 대기열. 신청자마다 접속당 한 번만 팝업으로 보여 주고, 크기는 항상 제한된다. */
final class FriendRequestQueue {
    static final int TOASTS=16;
    private final ArrayDeque<Entry> queued=new ArrayDeque<>();
    private final LinkedHashSet<UUID> shown=new LinkedHashSet<>();
    /** 새 알림. 이미 보여 줬거나 대기 중이거나 가득 찼으면 무시한다 (목록에는 남아 있으므로 친구창에서 처리할 수 있다). */
    boolean offer(Entry e){
        if(e==null||shown.contains(e.id())||queued.size()>=TOASTS)return false;
        shown.add(e.id());while(shown.size()>SocialProtocol.LIMIT*4)shown.remove(shown.iterator().next());
        queued.add(e);return true;
    }
    Entry poll(){return queued.poll();}
    int size(){return queued.size();}
    /** 서버의 받은 신청 목록에 더 이상 없는 알림은 버린다. 다시 신청이 오면 새로 알릴 수 있게 기록도 지운다. */
    void retain(Collection<UUID> pending){queued.removeIf(e->!pending.contains(e.id()));shown.retainAll(pending);}
    void remove(UUID id){queued.removeIf(e->e.id().equals(id));}
    void clear(){queued.clear();shown.clear();}
    /** 카드 기준 좌표가 어느 버튼 위인지: 1=수락, 2=거절, 0=없음. (게임 클래스를 건드리지 않아 단위 테스트가 가능하다) */
    static int hit(float x,float y){
        if(y<FriendRequestToast.BUTTON_Y||y>=FriendRequestToast.BUTTON_Y+FriendRequestToast.BUTTON_H)return 0;
        return x>=FriendRequestToast.ACCEPT_X&&x<FriendRequestToast.ACCEPT_X+FriendRequestToast.BUTTON_W?1:x>=FriendRequestToast.DECLINE_X&&x<FriendRequestToast.DECLINE_X+FriendRequestToast.BUTTON_W?2:0;
    }
}
