package school.chacademia.wildlife;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
class MainThreadSpawnGateTest {
 private final Thread main=Thread.currentThread();
 private final BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();
 private final ExecutorService workers=Executors.newFixedThreadPool(3);
 private final AtomicInteger cancellations=new AtomicInteger();
 private MainThreadSpawnGate gate(int limit,long millis){return new MainThreadSpawnGate(()->Thread.currentThread()==main,action->{queued.add(action);return cancellations::incrementAndGet;},limit,millis);}
 private Runnable next()throws Exception{var r=queued.poll(2,TimeUnit.SECONDS);assertNotNull(r,"main task was not dispatched");return r;}
 private boolean result(Future<Boolean> f)throws Exception{return f.get(2,TimeUnit.SECONDS);}
 @AfterEach void stop(){workers.shutdownNow();}
 @Test void mainThreadUsesExistingCheckDirectlyWithoutWaitingOrScheduling(){
  var gate=gate(1,250);assertTrue(gate.check(()->{assertSame(main,Thread.currentThread());return true;}));assertTrue(queued.isEmpty());assertEquals(0,gate.pendingCount());
 }
 @Test void asyncConditionTouchesWorldOnlyAfterMainThreadRunsTheTask()throws Exception{
  var gate=gate(16,500);var calls=new AtomicInteger();var f=workers.submit(()->gate.check(()->{assertSame(main,Thread.currentThread());calls.incrementAndGet();return true;}));
  var task=next();assertEquals(0,calls.get());task.run();assertTrue(result(f));assertEquals(1,calls.get());assertEquals(0,gate.pendingCount());
 }
 @Test void failedHabitatRemainsRefusedAfterMainThreadTransfer()throws Exception{
  var gate=gate(1,500);var f=workers.submit(()->gate.check(()->false));next().run();assertFalse(result(f));assertEquals(0,gate.pendingCount());
 }
 @Test void timeoutFailsClosedAndLateQueuedTaskNeverReadsTheWorld()throws Exception{
  var gate=gate(1,30);var calls=new AtomicInteger();var f=workers.submit(()->gate.check(()->{calls.incrementAndGet();return true;}));var late=next();
  assertFalse(result(f));assertEquals(0,gate.pendingCount());late.run();assertEquals(0,calls.get());assertTrue(cancellations.get()>0);assertTrue(gate.diagnostic().contains("timeout=1"));
 }
 @Test void pendingLimitRefusesExtraWorkWithoutExpandingTheSchedulerQueue()throws Exception{
  var gate=gate(1,1000);var first=workers.submit(()->gate.check(()->true));var task=next();assertEquals(1,gate.pendingCount());
  assertFalse(result(workers.submit(()->gate.check(()->true))));assertTrue(queued.isEmpty());assertEquals(1,gate.pendingCount());task.run();assertTrue(result(first));assertEquals(0,gate.pendingCount());assertTrue(gate.diagnostic().contains("rejected=1"));
 }
 @Test void reloadInvalidatesPendingResultsAndNextGenerationCanSucceed()throws Exception{
  var gate=gate(1,1000);var calls=new AtomicInteger();var old=workers.submit(()->gate.check(()->{calls.incrementAndGet();return true;}));var oldTask=next();gate.invalidate();
  assertFalse(result(old));oldTask.run();assertEquals(0,calls.get());assertEquals(0,gate.pendingCount());
  var fresh=workers.submit(()->gate.check(()->true));next().run();assertTrue(result(fresh));assertEquals(0,gate.pendingCount());
 }
 @Test void reloadDuringTheCheckCannotPublishItsOldGenerationResult()throws Exception{
  var gate=gate(1,1000);var f=workers.submit(()->gate.check(()->{gate.invalidate();return true;}));next().run();assertFalse(result(f));assertEquals(0,gate.pendingCount());
 }
 @Test void disableCancelsPendingWorkAndRejectsFurtherMainOrAsyncChecks()throws Exception{
  var gate=gate(1,1000);var calls=new AtomicInteger();var f=workers.submit(()->gate.check(()->{calls.incrementAndGet();return true;}));var task=next();gate.close();task.run();
  assertFalse(result(f));assertFalse(gate.check(()->{calls.incrementAndGet();return true;}));assertFalse(result(workers.submit(()->gate.check(()->true))));assertEquals(0,calls.get());assertEquals(0,gate.pendingCount());
 }
 @Test void expiredDeadlineBeforeExecutionPreventsWorldAccess()throws Exception{
  var clock=new AtomicLong();var gate=new MainThreadSpawnGate(()->Thread.currentThread()==main,action->{queued.add(action);return cancellations::incrementAndGet;},1,500,clock::get);var calls=new AtomicInteger();
  var f=workers.submit(()->gate.check(()->{calls.incrementAndGet();return true;}));var task=next();clock.set(TimeUnit.SECONDS.toNanos(1));task.run();assertFalse(result(f));assertEquals(0,calls.get());assertEquals(0,gate.pendingCount());
 }
 @Test void worldCheckExceptionFailsClosedAndReturnsItsPermit()throws Exception{
  var gate=gate(1,500);var f=workers.submit(()->gate.check(()->{throw new IllegalStateException("test failure");}));next().run();assertFalse(result(f));assertEquals(0,gate.pendingCount());
  var second=workers.submit(()->gate.check(()->true));next().run();assertTrue(result(second));
 }
 @Test void rejectedSchedulerSubmissionDoesNotLeakCapacity()throws Exception{
  var attempts=new AtomicInteger();var gate=new MainThreadSpawnGate(()->Thread.currentThread()==main,action->{if(attempts.incrementAndGet()==1)throw new RejectedExecutionException("disabled task");queued.add(action);return ()->{};},1,500);
  assertFalse(result(workers.submit(()->gate.check(()->true))));assertEquals(0,gate.pendingCount());var retry=workers.submit(()->gate.check(()->true));next().run();assertTrue(result(retry));
 }
 @Test void brokenDispatcherCannotExecuteWorldCodeOffThread()throws Exception{
  var calls=new AtomicInteger();var gate=new MainThreadSpawnGate(()->Thread.currentThread()==main,action->{action.run();return ()->{};},1,500);
  assertFalse(result(workers.submit(()->gate.check(()->{calls.incrementAndGet();return true;}))));assertEquals(0,calls.get());assertEquals(0,gate.pendingCount());
 }
 @Test void interruptedWorkerPreservesInterruptAndCancelsItsLateTask()throws Exception{
  var gate=gate(1,1000);var calls=new AtomicInteger();var outcome=new AtomicBoolean(true);var interrupted=new AtomicBoolean();
  var thread=new Thread(()->{outcome.set(gate.check(()->{calls.incrementAndGet();return true;}));interrupted.set(Thread.currentThread().isInterrupted());});thread.start();var late=next();thread.interrupt();thread.join(2000);
  assertFalse(thread.isAlive());assertFalse(outcome.get());assertTrue(interrupted.get());late.run();assertEquals(0,calls.get());assertEquals(0,gate.pendingCount());
 }
}
