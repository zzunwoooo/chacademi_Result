package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.WalletProtocol.Snapshot;
import static org.junit.jupiter.api.Assertions.*;

class WalletSubscriptionsTest {
    private static class Access implements WalletSubscriptions.Access {
        int reads,sends;boolean online=true;Snapshot value=new Snapshot(true,12450);
        Set<UUID> readIds=new HashSet<>();
        public boolean online(UUID id){return online;}
        public Snapshot read(UUID id){reads++;readIds.add(id);return value;}
        public void send(UUID id,Snapshot value){sends++;}
    }
    @Test void requestSpamCannotForceReadsAndUnchangedValuesAreCached(){
        var wallet=new WalletSubscriptions(5000,32);var a=new Access();var id=UUID.randomUUID();
        wallet.subscribe(id,0);wallet.process(0,a);assertEquals(1,a.sends);
        for(int i=1;i<5000;i++)wallet.subscribe(id,i);
        wallet.process(4999,a);assertEquals(1,a.reads);
        wallet.process(5000,a);assertEquals(2,a.reads);assertEquals(1,a.sends);
        a.value=new Snapshot(true,12345);wallet.process(10000,a);assertEquals(2,a.sends);
        wallet.subscribe(id,20000);wallet.process(40000,a);assertEquals(3,a.sends);
    }
    @Test void budgetIsBoundedAndQueueIsFair(){
        var wallet=new WalletSubscriptions(5000,2);var a=new Access();
        for(int i=0;i<7;i++)wallet.subscribe(UUID.randomUUID(),0);
        for(int tick=0;tick<40;tick++){
            int before=a.reads;wallet.process(tick*100,a);assertTrue(a.reads-before<=1);
        }
        assertEquals(7,a.readIds.size());assertEquals(7,a.reads);
    }
    @Test void halfSecondUpdatesSendOnlyChanges(){
        var wallet=new WalletSubscriptions(500,128);var a=new Access();var id=UUID.randomUUID();
        wallet.subscribe(id,0);wallet.process(0,a);
        a.value=new Snapshot(true,999);wallet.process(499,a);assertEquals(1,a.sends);
        wallet.process(500,a);assertEquals(2,a.sends);
        wallet.process(1000,a);assertEquals(3,a.reads);assertEquals(2,a.sends);
    }
    @Test void busyQueueNeverExceedsRollingSecondBudgetAndEventuallyServesEveryone(){
        var wallet=new WalletSubscriptions(500,32);var a=new Access();
        for(int i=0;i<100;i++)wallet.subscribe(UUID.randomUUID(),0);
        var times=new ArrayDeque<Long>();
        for(long now=0;now<5000;now+=50){
            int before=a.reads;wallet.process(now,a);
            int count=a.reads-before;assertTrue(count<=4);
            while(!times.isEmpty() && now-times.peekFirst()>=1000)times.removeFirst();
            for(int i=0;i<count;i++)times.addLast(now);
            assertTrue(times.size()<=32);
        }
        assertEquals(100,a.readIds.size());
    }
    @Test void expiredOfflineAndQuitSessionsStopWork(){
        var wallet=new WalletSubscriptions(5000,32);var a=new Access();var id=UUID.randomUUID();
        wallet.subscribe(id,0);wallet.process(60000,a);assertEquals(0,wallet.size());assertEquals(0,a.reads);
        wallet.subscribe(id,60001);wallet.remove(id);wallet.process(60002,a);assertEquals(0,a.reads);
        wallet.subscribe(id,60003);a.online=false;wallet.process(60004,a);assertEquals(0,wallet.size());
    }
    @Test void unavailableIsDistinctFromZeroAndProviderRecoveryIsSent(){
        var wallet=new WalletSubscriptions(1000,32);var a=new Access();
        wallet.subscribe(UUID.randomUUID(),0);a.value=Snapshot.unavailable();wallet.process(0,a);
        a.value=new Snapshot(true,0);wallet.process(1000,a);assertEquals(2,a.sends);
        a.value=Snapshot.unavailable();wallet.process(2000,a);assertEquals(3,a.sends);
    }
}
