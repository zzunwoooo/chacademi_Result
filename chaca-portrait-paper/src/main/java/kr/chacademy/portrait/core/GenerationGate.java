package kr.chacademy.portrait.core;
import java.util.concurrent.Callable;
/** Linearization point for shutdown versus final result commits. */
public final class GenerationGate {
 private volatile boolean open=true;
 public boolean running(){return open;}
 public synchronized void stop(){open=false;}
 public synchronized boolean commit(Callable<Boolean> action)throws Exception {
  return open && action.call();
 }
}
