package school.magiccodex.paper;

import org.bukkit.entity.*;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.*;

/** Loaded only when ModelEngine is available. Separate anchor leaves the original mob model untouched. */
final class TamingModelFx implements AutoCloseable {
    private final ArmorStand anchor;
    private final ModeledEntity modeled;
    private final ActiveModel model;
    private String animation="";
    TamingModelFx(LivingEntity target,boolean boss,double scale){
        model=ModelEngineAPI.createActiveModel(boss?"chacademia_taming_seal":"chacademia_taming_circle");
        if(model==null)throw new IllegalStateException("교화 모델을 ModelEngine에 등록하세요.");
        anchor=target.getWorld().spawn(target.getLocation(),ArmorStand.class,a->{a.setVisible(false);a.setMarker(true);a.setGravity(false);a.setInvulnerable(true);a.setPersistent(false);a.setSilent(true);a.addScoreboardTag("chacademia_taming_fx");});
        try{modeled=ModelEngineAPI.createModeledEntity(anchor);modeled.setBaseEntityVisible(false);model.setScale(scale);modeled.addModel(model,true);play("spawn");}
        catch(RuntimeException|LinkageError e){anchor.remove();model.destroy();throw e;}
    }
    void follow(LivingEntity e){if(anchor.isValid()&&e.isValid()&&anchor.getLocation().distanceSquared(e.getLocation())>.01)anchor.teleport(e.getLocation());}
    void play(String name){if(name.equals(animation))return;var h=model.getAnimationHandler();if(!animation.isEmpty())h.stopAnimation(animation);h.playAnimation(name,0.1,0.1,1,true);animation=name;}
    public void close(){try{modeled.destroy();ModelEngineAPI.removeModeledEntity(anchor.getUniqueId());}finally{anchor.remove();}}
}
