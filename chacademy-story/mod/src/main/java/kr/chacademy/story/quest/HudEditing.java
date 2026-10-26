package kr.chacademy.story.quest;
/** Optional adapter: no hard MagicCodex dependency, no changes to standalone Story HUD. */
final class HudEditing {
 interface Scope extends AutoCloseable {void close();}
 static Scope begin(Object graphics,float x,float y,float w,float h){try{var type=Class.forName("school.magiccodex.client.HudCustomization");var scope=(AutoCloseable)type.getMethod("beginExternal",Object.class,String.class,float.class,float.class,float.class,float.class).invoke(null,graphics,"quest",x,y,w,h);return ()->{try{scope.close();}catch(Exception ignored){}};}catch(ReflectiveOperationException|LinkageError e){return ()->{};}}
 static double[] pointer(double x,double y){try{return (double[])Class.forName("school.magiccodex.client.HudCustomization").getMethod("pointerExternal",String.class,double.class,double.class).invoke(null,"quest",x,y);}catch(ReflectiveOperationException|LinkageError e){return new double[]{x,y};}}
 /** MagicCodex HUD box {x,y,w,h} drawn this moment (e.g. "party"), or null without MagicCodex. */
 static float[] box(String id){try{return (float[])Class.forName("school.magiccodex.client.HudCustomization").getMethod("recentBoxExternal",String.class).invoke(null,id);}catch(ReflectiveOperationException|LinkageError|ClassCastException e){return null;}}
 static boolean editing(){try{return (Boolean)Class.forName("school.magiccodex.client.HudCustomization").getMethod("editingExternal").invoke(null);}catch(ReflectiveOperationException|LinkageError e){return false;}}
}
