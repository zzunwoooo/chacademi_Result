package school.chacademia.magicmythic;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import java.util.*;
/** Direct region query: deliberately never consult WorldGuard session/OP bypass. */
public final class MagicProtection implements Listener {
 private StateFlag flag; private boolean ready;
 interface Regions { Boolean state(Location at,String name); }
 private final Regions regions;
 public MagicProtection(){regions=this::query;}
 MagicProtection(Regions regions){this.regions=regions;ready=true;}
 static StateFlag claim(com.sk89q.worldguard.protection.flags.registry.FlagRegistry registry){
  if(registry.get("chacademi-magic")!=null)throw new IllegalStateException("chacademi-magic already registered; refusing ambiguous ownership");
  StateFlag owned=new StateFlag("chacademi-magic",true);registry.register(owned);return owned;
 }
 private Boolean query(Location at,String name){
  StateFlag selected=switch(name){case "pvp"->Flags.PVP;case "invincible"->Flags.INVINCIBILITY;default->flag;};
  var state=WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(at),(RegionAssociable)null,selected);
  return state==null?null:state==StateFlag.State.ALLOW;
 }
 private final Deque<Hit> hits=new ArrayDeque<>();
 /**
  * 전투 스펙 2026-10-26 (MagicArmor): VANILLA = 예전 그대로 (몹이 쓰거나 몹이 맞는 마법).
  * PVP = 플레이어→플레이어 마법: 마법 방어·저항을 여기서 계산해 줄인 값을 넣고, 바닐라 방어·보호 인첸트·저항은 0으로 (rewrite).
  * EXECUTE = 처형: 감소 0. FLAT = 이미 줄어든 피해(피해 나눠 받기): 더 줄이지 않음.
  * MOB = 몹 스펙 #109: 몹이 플레이어에게 쓰는 마법(몹 스킬) — PVP 와 같은 마법 방어·저항, 무적 시간 초기화는 안 함(여러 발이 한 번에 다 들어가지 않게, 예전처럼).
  */
 enum Mode { VANILLA, PVP, EXECUTE, FLAT, MOB }
 private static final class Hit { final LivingEntity caster,target; final Mode mode; EntityDamageEvent event; Hit(LivingEntity c,LivingEntity t,Mode m){caster=c;target=t;mode=m;} }
 public void register(){
  flag=claim(WorldGuard.getInstance().getFlagRegistry());ready=true;
 }
 public boolean at(Location location){
  if(!ready||location==null||location.getWorld()==null)return false;
  try{return !Boolean.FALSE.equals(regions.state(location,"magic"));}
  catch(RuntimeException|LinkageError e){return false;}
 }
 public boolean pair(LivingEntity caster,Location target){return caster!=null&&caster.isValid()&&at(caster.getLocation())&&at(target);}
 public boolean harm(LivingEntity caster,LivingEntity target){
  if(!pair(caster,target.getLocation()))return false;
  try{
   if(target instanceof Player){
    if(Boolean.TRUE.equals(regions.state(target.getLocation(),"invincible")))return false;
    if(caster instanceof Player && (Boolean.FALSE.equals(regions.state(target.getLocation(),"pvp"))||Boolean.FALSE.equals(regions.state(caster.getLocation(),"pvp"))))return false;
   }
   return true;
  }catch(RuntimeException|LinkageError e){return false;}
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
 public void observe(EntityDamageByEntityEvent event){
  Hit h=hits.peek();if(h!=null&&h.caster!=null&&event.getEntity().equals(h.target)&&event.getDamager().equals(h.caster))h.event=event;
 }
 /** 시전자 없는 FLAT/PVP 타격(피해 나눠 받기·플레이어가 건 독)의 결과. */
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
 public void observeSourceless(EntityDamageEvent event){
  Hit h=hits.peek();if(h!=null&&h.caster==null&&event.getEntity().equals(h.target))h.event=event;
 }
 /**
  * 전투 스펙: PVP/EXECUTE/FLAT 타격은 이미 계산한 값만 들어가게 바닐라 방어(ARMOR)·보호 인첸트(MAGIC)·저항(RESISTANCE)을 0으로,
  * 흡수 하트는 남은 피해 기준으로 다시. 다른 리스너(HIGH 의 피해 나눠 받기 등)가 setDamage 로 기본값을 바꾼 뒤에 돈다.
  */
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
 public void rewrite(EntityDamageEvent event){
  Hit h=hits.peek();if(h==null||h.mode==Mode.VANILLA||!event.getEntity().equals(h.target))return;
  if(h.caster!=null&&!(event instanceof EntityDamageByEntityEvent by&&by.getDamager().equals(h.caster)))return;
  clearDefenses(event,h.target.getAbsorptionAmount());
 }
 @SuppressWarnings("deprecation")
 static void clearDefenses(EntityDamageEvent e,double absorption){
  try{
   for(var m:new EntityDamageEvent.DamageModifier[]{EntityDamageEvent.DamageModifier.ARMOR,EntityDamageEvent.DamageModifier.MAGIC,EntityDamageEvent.DamageModifier.RESISTANCE})
    if(e.isApplicable(m))e.setDamage(m,0);
   if(e.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)){
    double rest=e.getFinalDamage()-e.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION);
    e.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION,-Math.min(Math.max(0,absorption),Math.max(0,rest)));
   }
  }catch(RuntimeException|LinkageError ignored){}
 }
 static boolean accepted(EntityDamageEvent event){return event!=null&&!event.isCancelled();}
 private int procDepth;
 /** True while a magic hit from this plugin is being applied (lets passives tell spell damage from weapon hits). */
 boolean magicDamaging(){return !hits.isEmpty();}
 /** 지금 target 이 맞고 있는 피해가 이미 마법 방어로 줄어든 PvP/처형/나눠 받기 타격인가 (Guards 피해 나눠 받기: 두 번 줄이지 않음). */
 boolean reducedHitOn(org.bukkit.entity.Entity target){Hit h=hits.peek();return h!=null&&h.mode!=Mode.VANILLA&&h.target.equals(target);}
 /** True while a passive's extra hit (미라지/미스틱 슈터 화살 등) is applied, so it cannot trigger passives again. */
 boolean inProc(){return procDepth>0;}
 boolean procDamage(LivingEntity caster,LivingEntity target,double amount){procDepth++;try{return damage(caster,target,amount);}finally{procDepth--;}}
 /** 플레이어가 다른 플레이어를 맞히는 마법인가 (전투 스펙 PvP 경로). */
 static boolean pvp(LivingEntity caster,LivingEntity target){return MagicArmor.current.enabled()&&caster instanceof Player&&target instanceof Player&&!caster.equals(target);}
 /** 몹(플레이어가 아닌 시전자)이 플레이어를 맞히는 마법인가 (몹 스펙 #109, config pvp.mob-magic-armor). */
 static boolean mobMagic(LivingEntity caster,LivingEntity target){var s=MagicArmor.current;return s.enabled()&&s.mobMagicArmor()&&caster!=null&&!(caster instanceof Player)&&target instanceof Player;}
 boolean damage(LivingEntity caster,LivingEntity target,double amount){return damage(caster,target,amount,pvp(caster,target)?Mode.PVP:mobMagic(caster,target)?Mode.MOB:Mode.VANILLA);}
 /** 처형: 방어·저항·흡수까지 넘는 피해, 감소 0 (몹 대상도 같은 경로 — 보호된 대상은 HitMech 가 미리 거름). */
 boolean execute(LivingEntity caster,LivingEntity target,double amount){return damage(caster,target,amount,Mode.EXECUTE);}
 boolean damage(LivingEntity caster,LivingEntity target,double amount,Mode mode){
  if(!harm(caster,target))return false;
  if(amount<=0){var probe=new EntityDamageByEntityEvent(caster,target,EntityDamageEvent.DamageCause.MAGIC,0);Bukkit.getPluginManager().callEvent(probe);return accepted(probe);}
  double applied=mode==Mode.PVP||mode==Mode.MOB?pvpReduced(target,amount):amount;
  Hit h=new Hit(caster,target,mode);hits.push(h);
  boolean ok;
  // 마법 발견 v2: 이 타격으로 죽어도 그 마법 처치로 세도록 피해 전에 기록 (체력이 가득 찬 상태였는지 = 한 방 처치 판정)
  var hp=target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
  var trace=SpellTrace.current();DiscoveryHooks.Last before=caster instanceof Player p0?DiscoveryHooks.pre(p0,target,trace,hp!=null&&target.getHealth()>=hp.getValue()-1e-6):null;
  try{resetInvulnerability(target,mode);target.damage(applied,caster);ok=accepted(h.event);}finally{hits.pop();}
  if(caster instanceof Player pl)DiscoveryHooks.post(pl,target,trace,ok,before);
  return ok;
 }
 /**
  * 시전자 없이 넣는 피해. pvp=true(플레이어가 플레이어에게 건 독 등): 마법 방어 적용 · flat=true(이미 줄어든 나눠 받기 피해): 감소 없음.
  * 둘 다 아니면 예전처럼 target.damage(amount).
  */
 boolean sourceless(LivingEntity target,double amount,boolean pvp,boolean flat){
  if(amount<=0||target==null||!target.isValid()||target.isDead())return false;
  Mode mode=MagicArmor.current.enabled()&&target instanceof Player?(flat?Mode.FLAT:pvp?Mode.PVP:Mode.VANILLA):Mode.VANILLA;
  if(mode==Mode.VANILLA){target.damage(amount);return true;}
  double applied=mode==Mode.PVP?pvpReduced(target,amount):amount;
  Hit h=new Hit(null,target,mode);hits.push(h);
  try{resetInvulnerability(target,mode);target.damage(applied);return accepted(h.event);}finally{hits.pop();}
 }
 /** 플레이어 대상 다단 히트가 바닐라 무적 시간(10틱)에 먹히지 않게: PvP 마법 타격 직전에 무적 시간을 0으로 (맞은 뒤에는 바닐라가 다시 채움). */
 private static void resetInvulnerability(LivingEntity target,Mode mode){if(mode!=Mode.VANILLA&&mode!=Mode.MOB&&target instanceof Player&&MagicArmor.current.resetInvulnerability())target.setNoDamageTicks(0);}
 /** 마법 방어 + 저항 (MagicArmor). 방어력 = ARMOR 속성, EPF = 갑옷 4칸 보호 인첸트 레벨 합. */
 static double pvpReduced(LivingEntity target,double amount){
  var s=MagicArmor.current;
  double armor=0;int epf=0,resistance=0;
  try{
   var a=target.getAttribute(org.bukkit.attribute.Attribute.ARMOR);if(a!=null)armor=a.getValue();
   var eq=target.getEquipment();if(eq!=null){var pieces=eq.getArmorContents();if(pieces!=null)for(var item:pieces)if(item!=null)epf+=item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION);}
   var r=target.getPotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE);if(r!=null)resistance=r.getAmplifier()+1;
  }catch(RuntimeException|LinkageError ignored){}
  return MagicArmor.reduced(amount,MagicArmor.reduction(s,armor,epf),MagicArmor.resistance(s,resistance));
 }
}
