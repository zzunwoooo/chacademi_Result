package kr.chacademy.portrait.core;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class GenerationGateTest {
 @Test void lateApiCompletionCannotCommitAfterStop()throws Exception{
  var gate=new GenerationGate();var writes=new AtomicInteger();gate.stop();
  assertFalse(gate.running());assertFalse(gate.commit(()->{writes.incrementAndGet();return true;}));assertEquals(0,writes.get());
 }
 @Test void previouslySuccessfulCommitSurvivesStop()throws Exception{
  var gate=new GenerationGate();var writes=new AtomicInteger();
  assertTrue(gate.commit(()->{writes.incrementAndGet();return true;}));gate.stop();
  assertEquals(1,writes.get());assertFalse(gate.commit(()->{writes.incrementAndGet();return true;}));assertEquals(1,writes.get());
 }
 @Test void inFlightCommitAndStopHaveOneOrderedBoundary()throws Exception{
  var gate=new GenerationGate();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)){
   Future<Boolean> commit=executor.submit(()->gate.commit(()->{entered.countDown();assertTrue(finish.await(2,TimeUnit.SECONDS));return true;}));
   assertTrue(entered.await(2,TimeUnit.SECONDS));Future<?> stop=executor.submit(gate::stop);
   finish.countDown();assertTrue(commit.get(2,TimeUnit.SECONDS));stop.get(2,TimeUnit.SECONDS);
   assertFalse(gate.running());assertFalse(gate.commit(()->true));
  }
 }
}
