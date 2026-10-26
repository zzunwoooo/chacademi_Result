package school.magiccodex.paper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class HouseSortingTest {
 @TempDir Path dir;
 @Test void allSixteenProfilesHaveUniqueFirstChoiceAndExpectedScores(){
  for(int t=0;t<4;t++)for(int d=0;d<4;d++){
   int[] s=HouseSorting.scores(t,d);assertArrayEquals(new int[]{t+d,t+3-d,3-t+d,6-t-d},s);
   int best=Arrays.stream(s).max().orElseThrow();assertEquals(1,Arrays.stream(s).filter(n->n==best).count());
   assertEquals(HouseSorting.preferred(t,d),HouseSorting.choose(t,d,new long[4],2));
  }
 }
 @Test void usesPreAssignmentCeilingAndStableLowerPreferenceTies(){
  assertEquals(0,HouseSorting.choose(3,3,new long[]{2,0,0,0},2)); // result becomes 3 versus 0, as specified
  assertEquals(1,HouseSorting.choose(3,3,new long[]{3,0,0,0},2));
  assertEquals(2,HouseSorting.choose(3,3,new long[]{4,1,0,0},2)); // equal score, lower population
  assertEquals(1,HouseSorting.choose(3,3,new long[]{4,0,0,0},2)); // equal population, declaration order
  assertEquals(3,HouseSorting.choose(3,3,new long[]{2,2,2,0},0));
  assertThrows(IllegalArgumentException.class,()->HouseSorting.scores(-1,0));
  assertThrows(IllegalArgumentException.class,()->HouseSorting.scores(0,4));
  assertThrows(IllegalArgumentException.class,()->HouseSorting.tolerance(-1));
  assertThrows(IllegalArgumentException.class,()->HouseSorting.tolerance(1000001));
 }
 @Test void wishAddsBonusButDoesNotOverrideStrongProfileOrPopulation(){
  assertArrayEquals(new int[]{2,4,3,4},HouseSorting.scores(1,1,1,1));
  assertEquals(1,HouseSorting.choose(1,1,new long[4],2,1,1));   // 2,3,3,4 → 루미나 +1 = 4 동점, 선언 순서로 루미나
  assertEquals(0,HouseSorting.choose(3,3,new long[4],2,3,1));   // 6,3,3,0 → 노크세르 +1 로는 못 뒤집음
  assertEquals(1,HouseSorting.preferred(1,1,1,1));
  assertEquals(3,HouseSorting.choose(1,1,new long[]{0,3,0,0},2,1,1)); // 희망이어도 인원 허용치가 먼저
  assertEquals(HouseSorting.choose(2,1,new long[4],2),HouseSorting.choose(2,1,new long[4],2,-1,1));
  assertThrows(IllegalArgumentException.class,()->HouseSorting.scores(0,0,4,1));
 }
 @Test void emptyHousesCountAndExistingAssignmentNeverRewrites()throws Exception{
  Path file=dir.resolve("sort.db");UUID id=UUID.randomUUID();
  try(var s=new SchoolStore(file)){
   assertArrayEquals(new long[4],s.houseCounts());s.house(UUID.randomUUID(),0);assertArrayEquals(new long[]{1,0,0,0},s.houseCounts());
   var first=s.assignHouse(id,3,3,2);assertTrue(first.created());assertEquals(0,first.house());
   var second=s.assignHouse(id,0,0,0);assertFalse(second.created());assertEquals(first.house(),second.house());assertEquals(0,s.savedHouse(id));
  }
  try(var s=new SchoolStore(file)){assertEquals(0,s.savedHouse(id));}
 }
 @Test void separateWorkersAndConnectionsSerializeCountsAndDuplicatePlayers()throws Exception{
  Path file=dir.resolve("concurrent.db");try(var init=new SchoolStore(file)){}
  var pool=Executors.newFixedThreadPool(4);UUID duplicate=UUID.randomUUID();var start=new CountDownLatch(1);
  try{
   var jobs=new ArrayList<Future<Integer>>();
   for(int worker=0;worker<4;worker++)jobs.add(pool.submit(()->{
    try(var s=new SchoolStore(file)){start.await();int created=s.assignHouse(duplicate,3,3,2).created()?1:0;
     for(int i=0;i<20;i++)assertTrue(s.assignHouse(UUID.randomUUID(),3,3,2).created());return created;}
   }));start.countDown();int created=0;for(var job:jobs)created+=job.get(30,TimeUnit.SECONDS);assertEquals(1,created);
   try(var s=new SchoolStore(file)){long[] counts=s.houseCounts();assertEquals(81,Arrays.stream(counts).sum());assertTrue(Arrays.stream(counts).max().orElseThrow()-Arrays.stream(counts).min().orElseThrow()<=3);}
  }finally{pool.shutdownNow();}
 }
 @Test void storageFailureRollsBackAndDoesNotClaimAssignment()throws Exception{
  Path file=dir.resolve("failure.db");UUID id=UUID.randomUUID();
  try(var s=new SchoolStore(file);var db=DriverManager.getConnection("jdbc:sqlite:"+file)){
   try(var q=db.createStatement()){q.execute("CREATE TRIGGER fail_house BEFORE INSERT ON houses BEGIN SELECT RAISE(ABORT,'test failure'); END");}
   assertThrows(SQLException.class,()->s.assignHouse(id,3,3,2));assertNull(s.savedHouse(id));assertArrayEquals(new long[4],s.houseCounts());
   try(var q=db.createStatement()){q.execute("DROP TRIGGER fail_house");}
   assertTrue(s.assignHouse(id,3,3,2).created());
  }
 }
 @Test void sharedSqlDialectSerializesIndependentServerWorkers()throws Exception{
  var settings=new school.magiccodex.database.DatabaseSettings(true,"jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
  Path unused=dir.resolve("unused.db");try(var init=new SchoolStore(unused,settings)){}
  UUID duplicate=UUID.randomUUID();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
  try{
   var jobs=new ArrayList<Future<Integer>>();
   for(int worker=0;worker<4;worker++)jobs.add(pool.submit(()->{
    try(var store=new SchoolStore(unused,settings)){start.await();int created=store.assignHouse(duplicate,3,3,2).created()?1:0;
     for(int i=0;i<20;i++)store.assignHouse(UUID.randomUUID(),3,3,2);return created;}
   }));start.countDown();int created=0;for(var job:jobs)created+=job.get(30,TimeUnit.SECONDS);assertEquals(1,created);
   try(var store=new SchoolStore(unused,settings)){long[] counts=store.houseCounts();assertEquals(81,Arrays.stream(counts).sum());assertTrue(Arrays.stream(counts).max().orElseThrow()-Arrays.stream(counts).min().orElseThrow()<=3);}
  }finally{pool.shutdownNow();}
 }
}
