package school.magiccodex.paper;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.database.ConnectionHolder;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionHolderTest {
    static final class Fake {
        boolean closed,valid=true,autoCommit=true;int setups,rollbacks;
        final Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
            case "isClosed"->closed;
            case "isValid"->!closed&&valid;
            case "close"->{closed=true;yield null;}
            case "getAutoCommit"->{if(closed)throw new SQLException("closed");yield autoCommit;}
            case "setAutoCommit"->{if(closed)throw new SQLException("closed");autoCommit=(Boolean)args[0];yield null;}
            case "rollback"->{if(closed)throw new SQLException("closed");rollbacks++;yield null;}
            case "commit"->{if(closed)throw new SQLException("closed");yield null;}
            case "hashCode"->System.identityHashCode(proxy);
            case "equals"->proxy==args[0];
            case "toString"->"fake";
            default->throw new UnsupportedOperationException(method.getName());
        });
    }
    final List<Fake> opened=new ArrayList<>();
    ConnectionHolder holder(boolean remote,long check)throws SQLException{
        return new ConnectionHolder(()->{var f=new Fake();opened.add(f);return f.connection;},db->opened.stream().filter(f->f.connection==db).findFirst().orElseThrow().setups++,remote,check);
    }

    @Test void closedSqliteConnectionIsReopenedWithSetupReapplied()throws Exception{
        var h=holder(false,0);var first=h.get();assertSame(first,h.get());
        opened.getFirst().closed=true;var second=h.get();
        assertNotSame(first,second);assertEquals(2,opened.size());assertEquals(1,opened.get(1).setups);
    }
    @Test void droppedRemoteConnectionIsValidatedAndReplaced()throws Exception{
        var h=holder(true,0);var first=h.get();opened.getFirst().valid=false;
        var second=h.get();assertNotSame(first,second);assertTrue(opened.getFirst().closed);assertEquals(1,opened.get(1).setups);
    }
    @Test void recentlyUsedRemoteConnectionSkipsValidation()throws Exception{
        var h=holder(true,60_000_000_000L);var first=h.get();opened.getFirst().valid=false;assertSame(first,h.get());
    }
    @Test void transactionNeverSwitchesConnectionAndRecoversAfterwards()throws Exception{
        var h=holder(true,0);var tx=h.begin();assertTrue(h.inTransaction());assertFalse(opened.getFirst().autoCommit);
        opened.getFirst().closed=true;assertSame(tx,h.get());assertThrows(SQLException.class,tx::commit);
        h.rollback();h.end();assertFalse(h.inTransaction());
        var next=h.get();assertNotSame(tx,next);assertTrue(opened.get(1).autoCommit);
        assertThrows(SQLException.class,()->{h.begin();h.begin();});h.end();
    }
    @Test void healthyRollbackStillRuns()throws Exception{
        var h=holder(false,0);h.begin();h.rollback();h.end();assertEquals(1,opened.getFirst().rollbacks);assertTrue(opened.getFirst().autoCommit);
        h.close();assertTrue(opened.getFirst().closed);assertThrows(SQLException.class,h::get);
    }
    @Test void failedSetupClosesTheNewConnection(){
        assertThrows(SQLException.class,()->new ConnectionHolder(()->{var f=new Fake();opened.add(f);return f.connection;},db->{throw new SQLException("pragma");},false));
        assertTrue(opened.getFirst().closed);
    }
}
