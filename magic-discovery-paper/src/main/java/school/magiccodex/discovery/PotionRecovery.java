package school.magiccodex.discovery;
import java.util.*;
/** Conservative one-splash evidence; unrelated/overlapping healing fails closed. */
final class PotionRecovery {
 record Sample(double before,double maximum,double expected){}
 final UUID actor;final Map<UUID,Sample> samples;final Set<UUID> accepted=new HashSet<>(),invalid=new HashSet<>();
 PotionRecovery(UUID actor,Map<UUID,Sample> samples){this.actor=actor;this.samples=Map.copyOf(samples);}
 void regain(UUID id,boolean magic,boolean cancelled,double amount){var sample=samples.get(id);if(sample==null)return;if(!magic||cancelled||amount<=0||sample.before()>=sample.maximum()||!Double.isFinite(amount)||Math.abs(amount-sample.expected())>0.0001||!accepted.add(id))invalid.add(id);}
 void invalidate(UUID id){invalid.add(id);}
 Set<UUID> actual(Map<UUID,Double> health){var result=new HashSet<UUID>();for(UUID id:accepted){var sample=samples.get(id);double gained=health.getOrDefault(id,sample.before())-sample.before();if(!invalid.contains(id)&&gained>0&&Math.abs(gained-Math.min(sample.expected(),sample.maximum()-sample.before()))<0.0001)result.add(id);}return Set.copyOf(result);}
 static boolean qualifies(UUID actor,Set<UUID> healed,Set<UUID> party){return healed.contains(actor)&&party.contains(actor)&&healed.stream().filter(id->!id.equals(actor)&&party.contains(id)).limit(2).count()==2;}
}
