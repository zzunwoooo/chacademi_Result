package school.magiccodex.client;

import java.lang.reflect.Method;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;

/** Optional Motion 0.3 API: server-owned inventory; never fabricates ownership or an equipped badge. */
final class BroomUiLink {
    private static Class<?> api;
    private static boolean warned;
    private static Object call(String name, Class<?>[] types, Object... args) {
        if (!FabricLoader.getInstance().isModLoaded("chacademy_motion")) return null;
        try {
            if (api == null) api = Class.forName("kr.chacademy.motion.api.BroomApi");
            Method method = api.getMethod(name, types);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warned) { warned = true; org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Broom equipment API unavailable", e); }
            return null;
        }
    }
    private static Object call(String name) { return call(name,new Class<?>[0]); }
    static boolean available() { return Boolean.TRUE.equals(call("available")); }
    static void refresh() { call("refresh"); }
    static String[] owned() { var value=call("owned");return value instanceof String[] a?Arrays.stream(a).filter(s->s!=null&&!s.isBlank()).distinct().limit(30).toArray(String[]::new):new String[0]; }
    static String equipped() { var value=call("equipped");return value instanceof String s?s:""; }
    static long version() { var value=call("version");return value instanceof Number n?n.longValue():0; }
    static float mastery() { var value=call("mastery");return value instanceof Number n?Math.clamp(n.floatValue(),0,1):0; }
    static String text(String method,String id) { var value=call(method,new Class<?>[]{String.class},id);return value instanceof String s?s:id; }
    static String[] labels() { var value=call("gaugeLabels");return value instanceof String[] a?a:new String[0]; }
    static String[] gaugeTexts(String id) { var value=call("gaugeTexts",new Class<?>[]{String.class},id);return value instanceof String[] a?a:new String[0]; }
    static float[] gauges(String id) { var value=call("gauges",new Class<?>[]{String.class},id);return value instanceof float[] a?a:new float[0]; }
    static void equip(String id) { if(available()&&Arrays.asList(owned()).contains(id))call("equip",new Class<?>[]{String.class},id); }
    static boolean render(DrawContext c,String id,float x,float y,float length,float yaw,float pitch,float spin) {
        return Boolean.TRUE.equals(call("renderGui",new Class<?>[]{Object.class,String.class,float.class,float.class,float.class,float.class,float.class,float.class},c,id,x,y,length,yaw,pitch,spin));
    }
}
