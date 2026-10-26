package school.magiccodex.client;
import java.util.function.BooleanSupplier;
/** Confirmed layout-only reset; cancellation never touches state or files. */
final class HudLayoutReset {
 private HudLayoutReset(){}
 static boolean apply(boolean confirmed,HudLayoutStore layouts,Runnable cancelGesture,BooleanSupplier resetSize,BooleanSupplier saveLayouts){
  if(!confirmed)return false;
  cancelGesture.run();layouts.reset();
  boolean sizeSaved=resetSize.getAsBoolean();boolean layoutsSaved=saveLayouts.getAsBoolean();
  return sizeSaved&&layoutsSaved;
 }
}
