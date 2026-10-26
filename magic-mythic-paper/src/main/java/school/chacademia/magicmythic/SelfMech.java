package school.chacademia.magicmythic;
import org.bukkit.potion.*;import org.bukkit.util.Vector;
/** Replaces delayed built-in self effects so execution cannot bypass the magic flag. */
final class SelfMech extends Mech {
 private final boolean potion;
 SelfMech(MagicMythicPlugin plugin,Params p,boolean potion){super(plugin,p);this.potion=potion;}
 boolean run(Ctx c){
  if(!plugin.protection().pair(c.caster(),c.caster().getLocation()))return false;
  if(potion){var type=PotionEffectType.getByName(p.s("type",""));if(type==null)return false;return c.caster().addPotionEffect(new PotionEffect(type,Math.clamp(p.i("duration",20),1,12000),Math.clamp(p.i("level",0),0,10)));}
  // 빗자루 비행 중에는 시전자 위치를 바꾸는 마법(상승기류 등)이 나가지 않음 (움직임은 모드의 빗자루 물리가 정함)
  if(c.caster() instanceof org.bukkit.entity.Player rider&&BroomLink.riding(rider))return false;
  var v=new Vector(p.d("x",0),p.d("y",0),p.d("z",0));if(!plugin.protection().pair(c.caster(),c.caster().getLocation().add(v)))return false;c.caster().setVelocity(v);return true;
 }
}
