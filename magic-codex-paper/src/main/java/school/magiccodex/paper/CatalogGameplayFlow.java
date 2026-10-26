package school.magiccodex.paper;
import java.util.function.*;
/** Cost boundary: no visual on failed preparation, no refund after visual commitment. */
final class CatalogGameplayFlow {
 static Boolean cast(boolean prepared,boolean routed,Supplier<Boolean> visual,BooleanSupplier execute,Consumer<Throwable> log){
  if(!prepared)return false;
  Boolean shown=visual.get();if(Boolean.FALSE.equals(shown))return false;if(!routed)return shown;
  boolean done;
  try{done=execute.getAsBoolean();if(!done)log.accept(new IllegalStateException("Gameplay refused"));}
  catch(RuntimeException|LinkageError failure){log.accept(failure);return shown!=null;}
  return shown!=null||done;
 }
}
