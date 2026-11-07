package kr.kkaegul.core.casino;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/** ModelEngine 이 있으면 엔티티에 모델을 씌운다 (리플렉션, 없으면 아무것도 안 함). 성공하면 true */
public final class ModelEngineHook {
    private ModelEngineHook() {}

    public static boolean present() { return Bukkit.getPluginManager().getPlugin("ModelEngine") != null; }

    public static boolean apply(Entity e, String modelId) {
        if (!present() || e == null || modelId == null || modelId.isBlank()) return false;
        try {
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            Object modeled = api.getMethod("createModeledEntity", Entity.class).invoke(null, e);
            Object active = api.getMethod("createActiveModel", String.class).invoke(null, modelId);
            if (modeled == null || active == null) return false;
            Class<?> am = Class.forName("com.ticxo.modelengine.api.model.ActiveModel");
            modeled.getClass().getMethod("addModel", am, boolean.class).invoke(modeled, active, true);
            try { modeled.getClass().getMethod("setBaseEntityVisible", boolean.class).invoke(modeled, false); } catch (NoSuchMethodException ignored) { }
            return true;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("KkaegulCore").warning("[KkaegulCore] ModelEngine 모델 적용 실패 " + modelId + ": " + t);
            return false;
        }
    }

    /** 씌운 모델의 애니메이션 재생 */
    public static void play(Entity e, String anim) {
        if (!present() || e == null) return;
        try {
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            Object modeled = api.getMethod("getModeledEntity", Entity.class).invoke(null, e);
            if (modeled == null) return;
            Object models = modeled.getClass().getMethod("getModels").invoke(modeled);
            for (Object m : ((java.util.Map<?, ?>) models).values()) {
                Object h = m.getClass().getMethod("getAnimationHandler").invoke(m);
                for (var meth : h.getClass().getMethods())
                    if (meth.getName().equals("playAnimation") && meth.getParameterCount() == 5) { meth.invoke(h, anim, 0.1, 0.1, 1.0, true); break; }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("KkaegulCore").warning("[KkaegulCore] ModelEngine 애니메이션 실패 " + anim + ": " + t);
        }
    }
}
