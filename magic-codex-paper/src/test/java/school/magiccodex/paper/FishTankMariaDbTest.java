package school.magiccodex.paper;
import java.lang.reflect.*;import java.nio.file.*;import java.sql.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.database.DatabaseSettings;import school.magiccodex.paper.FishTank.Fish;import school.magiccodex.paper.FishTank.Put;import school.magiccodex.paper.FishTankSql.Action;
/** Hosting-only isolated tables; credentials never appear in output. */
class FishTankMariaDbTest {
 final String prefix="codex_fish_it_"+UUID.randomUUID().toString().substring(0,8).replace("-","");
 final UUID owner=UUID.randomUUID();DatabaseSettings settings;
 @BeforeEach void configure()throws Exception{
  String file=System.getenv().getOrDefault("CHACA_FISH_TEST_DB_CONFIG","");Assumptions.assumeTrue(!file.isBlank(),"hosting DB integration only");settings=DatabaseSettings.load(Path.of(file));assertTrue(settings.mariaDb());
 }
 String sql(String s){return s.replace("codex_fish_tank",prefix+"_tank").replace("codex_fish_lock",prefix+"_lock").replace("codex_fish_mutex",prefix+"_mutex");}
 Connection connection()throws Exception{
  Connection raw;try{raw=settings.connect(Path.of("unused-test.sqlite"));settings.configure(raw);}catch(Exception e){throw new AssertionError("Hosting MariaDB connection failed: "+e.getClass().getSimpleName());}
  return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,m,args)->{
   try{
    if(m.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String s)args[0]=sql(s);
    Object out=m.invoke(raw,args);
    if(m.getName().equals("createStatement")){
     var statement=(Statement)out;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,method,values)->{try{if(values!=null&&values.length>0&&values[0] instanceof String s)values[0]=sql(s);return method.invoke(statement,values);}catch(InvocationTargetException e){throw e.getCause();}});
    }return out;
   }catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 FishTankSql store(Connection c)throws Exception{var s=new FishTankSql(c,true);s.init();return s;}
 Fish fish(){return new Fish(UUID.randomUUID().toString(),"fish_001",25.5,1760000000000L);}
 @AfterEach void cleanup()throws Exception{
  if(settings==null)return;assertTrue(prefix.matches("codex_fish_it_[a-f0-9]{8}"));
  try(var c=settings.connect(Path.of("unused-test.sqlite"));var s=c.createStatement()){for(String suffix:List.of("_tank","_lock","_mutex"))s.execute("DROP TABLE IF EXISTS "+prefix+suffix);}
 }
 @Test void twoServersShareAtomicCapacityAndOneWinnerClaims()throws Exception{
  try(var a=connection();var b=connection()){
   var one=store(a);var two=store(b);one.put(owner,fish(),2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
   try{var x=pool.submit(()->{start.await();return one.put(owner,fish(),2);});var y=pool.submit(()->{start.await();return two.put(owner,fish(),2);});start.countDown();var values=List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));assertEquals(1,values.stream().filter(p->p==Put.OK).count());assertEquals(1,values.stream().filter(p->p==Put.FULL).count());
    var f=one.list(owner).getFirst();var gate=new CountDownLatch(1);var first=pool.submit(()->{gate.await();return one.claim(owner,List.of(f),Action.SELL);});var second=pool.submit(()->{gate.await();return two.claim(owner,List.of(f),Action.ITEMIZE);});gate.countDown();assertEquals(1,first.get(20,TimeUnit.SECONDS).size()+second.get(20,TimeUnit.SECONDS).size());
   }finally{pool.shutdownNow();}
  }
 }
 @Test void locksRefundAndGenerationTokensPersistAcrossConnections()throws Exception{
  Fish item;try(var a=connection();var b=connection()){
   var one=store(a);var two=store(b);var f=fish();assertEquals(Put.OK,one.put(owner,f,100));var stale=two.list(owner);one.locks(owner,Set.of(f.fish()));assertTrue(two.claim(owner,stale,Action.SELL).isEmpty());one.locks(owner,Set.of());
   var sale=two.claim(owner,stale,Action.SELL);assertEquals(1,sale.size());assertTrue(two.finish(owner,sale,Action.SELL,false));assertEquals(f,one.list(owner).getFirst());
   var take=one.claim(owner,List.of(f),Action.ITEMIZE);item=take.getFirst();one.finish(owner,take,Action.ITEMIZE,true);
  }
  try(var c=connection()){
   var s=store(c);assertEquals(Put.OK,s.put(owner,item,100));var take=s.claim(owner,s.list(owner),Action.ITEMIZE);s.finish(owner,take,Action.ITEMIZE,true);assertEquals(Put.DUPLICATE,s.put(owner,item,100));assertEquals(Put.OK,s.put(owner,take.getFirst(),100));
   var sale=s.claim(owner,s.list(owner),Action.SELL);s.finish(owner,sale,Action.SELL,true);assertEquals(Put.DUPLICATE,s.put(owner,take.getFirst(),100));
  }
 }
}