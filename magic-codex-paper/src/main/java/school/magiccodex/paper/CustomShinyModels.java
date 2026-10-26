package school.magiccodex.paper;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import org.bukkit.entity.LivingEntity;

/** Dedicated textured models; eligibility is checked by ShinyBridge using registered Mythic identity. */
final class CustomShinyModels {
 static void apply(LivingEntity entity,boolean shiny){
  var modeled=ModelEngineAPI.getModeledEntity(entity.getUniqueId());
  if(modeled==null){
   if(entity.getScoreboardTags().contains("chacademia_custom"))throw new IllegalStateException("Custom model is not attached yet");
   return; // Registered vanilla-looking Mythic creatures legitimately have no ModelEngine model.
  }
  ShinyModelSwap.apply(new ShinyModelSwap.Models<ActiveModel>(){
   public java.util.Map<String,ActiveModel> current(){return modeled.getModels();}
   public ActiveModel create(String id){return ModelEngineAPI.createActiveModel(id);}
   public void scale(ActiveModel replacement,ActiveModel original){replacement.setScale(original.getScale().x());}
   public void remove(String id){modeled.removeModel(id);}
   public void add(ActiveModel model){modeled.addModel(model,true);}
   public void destroy(ActiveModel model){model.destroy();}
  },shiny);
 }
 static String targetModelId(String id,boolean shiny){return ShinyModelSwap.target(id,shiny);}
 private CustomShinyModels(){}
}