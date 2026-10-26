package school.magiccodex.paper;
import java.util.*;

/** Prepare every replacement before detaching anything; commit identity only after this succeeds. */
final class ShinyModelSwap {
 interface Models<M> {
  Map<String,M> current(); M create(String id); void scale(M replacement,M original);
  void remove(String id); void add(M model); void destroy(M model);
 }
 private record Change<M>(String oldId,String newId,M oldModel,M replacement) {}
 static boolean customAppearance(boolean tagged,boolean registeredMythic){return tagged||registeredMythic;}
 static String target(String id,boolean shiny){
  String normal=switch(id){
   case "doxy","doxy_shiny" -> "doxy";
   case "fenrir_mother","fenrir_mother_shiny" -> "fenrir_mother";
   case "fenrir_pup","fenrir_pup_shiny" -> "fenrir_pup";
   case "goblin","goblin_shiny" -> "goblin";
   default -> null;
  };
  if(normal!=null)return normal+(shiny?"_shiny":"");
  if(!id.startsWith("ca_"))return null;
  normal=id.endsWith("_s")?id.substring(0,id.length()-2):id;
  return normal+(shiny?"_s":"");
 }
 static <M> void apply(Models<M> models,boolean shiny){
  var changes=new ArrayList<Change<M>>();boolean recognized=false;
  try{
   for(var entry:Map.copyOf(models.current()).entrySet()){
    String next=target(entry.getKey(),shiny);if(next==null)continue;recognized=true;
    if(next.equals(entry.getKey()))continue;
    M replacement=models.create(next);
    if(replacement==null)throw new IllegalStateException("Missing rarity model: "+next);
    changes.add(new Change<>(entry.getKey(),next,entry.getValue(),replacement));
    models.scale(replacement,entry.getValue());
   }
   if(!recognized)throw new IllegalStateException("No registered rarity model attached");
  }catch(RuntimeException|LinkageError failure){
   for(var c:changes)cleanup(()->models.destroy(c.replacement),failure);throw failure;
  }
  var attempted=new ArrayList<Change<M>>();
  try{
   for(var c:changes){attempted.add(c);models.remove(c.oldId);models.add(c.replacement);}
  }catch(RuntimeException|LinkageError failure){
   Collections.reverse(attempted);
   for(var c:attempted){cleanup(()->models.remove(c.newId),failure);cleanup(()->models.add(c.oldModel),failure);}
   for(var c:changes)cleanup(()->models.destroy(c.replacement),failure);throw failure;
  }
  for(var c:changes)models.destroy(c.oldModel);
 }
 private static void cleanup(Runnable work,Throwable failure){try{work.run();}catch(RuntimeException|LinkageError error){failure.addSuppressed(error);}}
 private ShinyModelSwap(){}
}