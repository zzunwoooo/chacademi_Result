package kr.chacademy.halloweensky;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/** Iris 셰이더가 켜져 있는지 (Iris 가 없으면 항상 false). */
final class IrisCheck {
    private static Object api;
    private static Method inUse;
    private static boolean tried;

    private IrisCheck() {}

    static boolean shadersOn() {
        if (!tried) {
            tried = true;
            if (FabricLoader.getInstance().isModLoaded("iris")) {
                try {
                    Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | LinkageError e) {
                    api = null;
                }
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
