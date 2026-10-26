package school.chacademia.wildlife;

import com.ticxo.modelengine.api.ModelEngineAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * 등급별 ModelEngine 모델 크기(예: 3성 1.35배). 모델과 히트박스를 같이 키운다.
 * ModelEngine이 없거나 API가 달라도 플러그인은 그대로 돈다(이 클래스만 조용히 꺼짐).
 * 이미 같은 크기면 아무것도 안 함 → 10초 정리 루프에서 다시 불러도 싸다(재접속·청크 재로드 후 복구용).
 */
final class MobScale {
    private static boolean broken;

    static void apply(Entity entity, double scale) {
        if (broken || scale <= 0 || Math.abs(scale - 1) < 1e-3) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return;
        try {
            var modeled = ModelEngineAPI.getModeledEntity(entity.getUniqueId());
            if (modeled == null) return;
            for (var model : modeled.getModels().values()) {
                if (matches(model.getScale(), scale) && matches(model.getHitboxScale(), scale)) continue;
                model.setScale(scale);
                model.setHitboxScale(scale);
            }
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            Bukkit.getLogger().warning("[ChAcademiaWildlife] ModelEngine 모델 크기 조절을 끕니다: " + e);
        }
    }

    static boolean matches(org.joml.Vector3fc value, double scale) {
        return value != null && Math.abs(value.x()-scale)<1e-3 && Math.abs(value.y()-scale)<1e-3 && Math.abs(value.z()-scale)<1e-3;
    }

    private MobScale() {}
}
