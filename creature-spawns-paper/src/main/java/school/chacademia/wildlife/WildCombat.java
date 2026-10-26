package school.chacademia.wildlife;

import io.lumine.mythic.bukkit.MythicBukkit;
import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * 야생 몹 전투 보정 (오너 2026-10-23):
 * 1) 멀리서 때린 플레이어를 끝까지 쫓아감 — 맞은 순간 그 플레이어를 대상으로 잡고, 추적 거리(FOLLOW_RANGE)를 그 거리+16(최대 chase-max)까지 늘림.
 * 2) far-distance 넘게 떨어져 맞으면 원거리·접근 몹 스킬(ranged-skills.yml)부터 씀 — 몹당 skill-cooldown초에 한 번.
 * 3) 전투가 아닐 때(out-of-combat-seconds 동안 주고받은 피해 없음) 2초마다 최대 체력의 regen-percent%씩 회복.
 * 1-1) 늘린 추적 거리는 전투가 끝나면(out-of-combat-seconds) 원래 값으로 되돌림 (2026-10-25). 원래 값·늘린 값은 몹 PDC에 기록,
 *      언로드·플러그인 종료·기능 꺼짐·크래시 뒤 재로드 때도 복구. 그 사이 다른 플러그인이 값을 바꿨으면 덮어쓰지 않음.
 * 대상: Mythic 이름이 CA_Wild_ 인 몹. AI가 꺼졌거나 무적인 개체(교화 봉인 등)는 회복하지 않음. 메인 스레드 전용.
 */
final class WildCombat implements Listener {
    record Settings(boolean enabled,double farDistance,double chaseMax,long skillCooldownMs,long outOfCombatMs,double regenFraction) {
        static Settings parse(ConfigurationSection s) {
            if(s==null) return new Settings(true,8,96,4000,10000,0.05);
            return new Settings(s.getBoolean("enabled",true),Math.max(0,s.getDouble("far-distance",8)),Math.clamp(s.getDouble("chase-max",96),16,128),
                (long)(Math.max(0.5,s.getDouble("skill-cooldown",4))*1000),(long)(Math.max(1,s.getDouble("out-of-combat-seconds",10))*1000),
                Math.clamp(s.getDouble("regen-percent",5),0,100)/100);
        }
    }

    private final WildlifePlugin plugin;
    private Settings settings=Settings.parse(null);
    private Map<String,List<String>> ranged=Map.of();
    private final Map<UUID,Long> lastCombat=new HashMap<>(), lastSkill=new HashMap<>();
    /** 추적 거리를 늘려 둔 몹 (복구 대상). 4096 정리 대상 아님: 복구·사망·언로드 때만 빠짐. */
    private final Map<UUID,LivingEntity> raised=new HashMap<>();
    private final NamespacedKey followOriginal, followRaised;
    private long chased,skillCasts,regenTicks,followRestored;

    WildCombat(WildlifePlugin plugin) {
        this.plugin=plugin;
        followOriginal=new NamespacedKey(plugin,"follow_original");
        followRaised=new NamespacedKey(plugin,"follow_raised");
    }

    void reload(ConfigurationSection section) {
        settings=Settings.parse(section);
        var file=new File(plugin.getDataFolder(),"ranged-skills.yml");
        if(!file.exists()) plugin.saveResource("ranged-skills.yml",false);
        var y=YamlConfiguration.loadConfiguration(file).getConfigurationSection("ranged-skills");
        var next=new HashMap<String,List<String>>();
        if(y!=null) for(String type:y.getKeys(false)) { var list=y.getStringList(type); if(!list.isEmpty()) next.put(type,List.copyOf(list)); }
        ranged=Map.copyOf(next);
    }

    private static String wildType(Entity e) {
        if(!(e instanceof LivingEntity)||e instanceof Player) return null;
        var mob=MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId());
        if(mob.isEmpty()) return null;
        String type=mob.get().getType().getInternalName();
        return type.startsWith("CA_Wild_")?type:null;
    }

    private static Player attacker(Entity damager) {
        if(damager instanceof Player p) return p;
        if(damager instanceof Projectile pr&&pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent e) {
        if(!settings.enabled()) return;
        long now=System.currentTimeMillis();
        // 몹이 플레이어를 때려도 전투 중
        Entity source=e.getDamager() instanceof Projectile pr&&pr.getShooter() instanceof Entity s?s:e.getDamager();
        if(e.getEntity() instanceof Player&&wildType(source)!=null) { lastCombat.put(source.getUniqueId(),now); return; }
        String type=wildType(e.getEntity());
        if(type==null) return;
        lastCombat.put(e.getEntity().getUniqueId(),now);
        Player p=attacker(e.getDamager());
        if(p==null||p.getGameMode()==GameMode.SPECTATOR||p.getGameMode()==GameMode.CREATIVE||!(e.getEntity() instanceof Mob mob)) return;
        if(!mob.getWorld().equals(p.getWorld())) return;
        double dist=mob.getLocation().distance(p.getLocation());
        var follow=mob.getAttribute(Attribute.FOLLOW_RANGE);
        if(follow!=null) {
            double want=Math.min(settings.chaseMax(),dist+16);
            double base=follow.getBaseValue();
            if(base<want) {
                var pdc=mob.getPersistentDataContainer();
                // 처음 늘릴 때의 값만 원래 값으로 기록 (이미 늘린 몹을 더 늘리면 원래 값은 그대로).
                // 단, 우리가 늘린 값과 지금 값이 다르면 그 사이 다른 플러그인이 바꾼 것 → 지금 값을 새 원래 값으로.
                Double prevSet=pdc.get(followRaised,PersistentDataType.DOUBLE);
                if(!pdc.has(followOriginal,PersistentDataType.DOUBLE)||prevSet==null||Math.abs(prevSet-base)>1e-6) pdc.set(followOriginal,PersistentDataType.DOUBLE,base);
                pdc.set(followRaised,PersistentDataType.DOUBLE,want);
                follow.setBaseValue(want);
                raised.put(mob.getUniqueId(),mob);
            }
        }
        if(mob.getTarget()!=p) { mob.setTarget(p); chased++; }
        if(dist<settings.farDistance()) return;
        var skills=ranged.get(type);
        if(skills==null) return;
        Long last=lastSkill.get(mob.getUniqueId());
        if(last!=null&&now-last<settings.skillCooldownMs()) return;
        lastSkill.put(mob.getUniqueId(),now);
        // 대상 설정이 AI에 반영된 다음 틱에 시전 (@target 이 방금 때린 플레이어를 가리키게)
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!mob.isValid()||mob.isDead()) return;
            for(String skill:skills) {
                try { if(MythicBukkit.inst().getAPIHelper().castSkill(mob,skill)) { skillCasts++; return; } }
                catch(RuntimeException|LinkageError error) { plugin.warnOnce("원거리 몹 스킬 시전 실패 "+skill,error); return; }
            }
        });
    }

    @EventHandler public void death(EntityDeathEvent e) { UUID id=e.getEntity().getUniqueId(); lastCombat.remove(id); lastSkill.remove(id); raised.remove(id); }

    // ------------------------------------------------------------------ 추적 거리 복구
    /** Keep retry records until restoration succeeds or an external change confirms ownership is lost. */
    private void restoreFollow(LivingEntity e) {
        UUID id=e.getUniqueId();
        raised.put(id,e); // Entities discovered on load must also remain retryable after failure.
        var pdc=e.getPersistentDataContainer();
        Double original=pdc.get(followOriginal,PersistentDataType.DOUBLE), set=pdc.get(followRaised,PersistentDataType.DOUBLE);
        if(original==null||set==null) { raised.remove(id); return; }
        var follow=e.getAttribute(Attribute.FOLLOW_RANGE);
        if(follow==null) return; // Attribute may be temporarily unavailable; preserve ownership.
        if(Math.abs(follow.getBaseValue()-set)<=1e-6) {
            follow.setBaseValue(original); // Exceptions leave PDC and raised intact for the next pass.
            followRestored++;
        } // External changes are intentionally not overwritten: abandon ownership only.
        pdc.remove(followOriginal); pdc.remove(followRaised);
        raised.remove(id);
    }
    /** 회복 제외 조건(regen 0·만피·AI 없음·무적)과 무관하게 먼저 돈다. 기능이 꺼져 있으면 전투 여부와 상관없이 전부 복구. */
    private void restorePass(long now) {
        if(raised.isEmpty()) return;
        for(var e:new ArrayList<>(raised.values())) {
            try {
                if(!e.isValid()||e.isDead()) { raised.remove(e.getUniqueId()); continue; } // 언로드는 이벤트에서, 사망은 기록 의미 없음
                Long last=lastCombat.get(e.getUniqueId());
                if(settings.enabled()&&last!=null&&now-last<settings.outOfCombatMs()) continue; // 아직 전투 중
                restoreFollow(e);
            } catch(RuntimeException|LinkageError error) { plugin.warnOnce("야생 몹 추적 거리 복구 중 오류",error); }
        }
    }
    /** 청크 언로드: 저장되는 값이 원래 값이 되도록 복구. */
    @EventHandler public void unload(EntitiesUnloadEvent event) {
        if(raised.isEmpty()) return;
        for(var e:event.getEntities()) if(e instanceof LivingEntity le&&raised.containsKey(le.getUniqueId())) {
            try { restoreFollow(le); }
            catch(RuntimeException|LinkageError error) { plugin.warnOnce("Wild follow unload restore failed",error); }
        }
    }
    /** 크래시 등으로 기록이 남은 채 다시 로드된 몹: 바로 복구. */
    @EventHandler public void load(EntitiesLoadEvent event) {
        for(var e:event.getEntities()) if(e instanceof LivingEntity le) {
            try { if(le.getPersistentDataContainer().has(followRaised,PersistentDataType.DOUBLE)) restoreFollow(le); }
            catch(RuntimeException|LinkageError error) { raised.put(le.getUniqueId(),le); plugin.warnOnce("Wild follow load restore failed",error); }
        }
    }
    /** 플러그인 종료: 늘려 둔 몹 전부 복구. */
    void restoreAll() {
        for(var e:new ArrayList<>(raised.values())) {
            try { if(e.isValid()) restoreFollow(e); else raised.remove(e.getUniqueId()); }
            catch(RuntimeException|LinkageError error) { plugin.warnOnce("Wild follow shutdown restore failed",error); }
        }
    }

    /** 2초마다 (onEnable에서 예약). */
    void regenTick() {
        long now=System.currentTimeMillis();
        restorePass(now);
        if(!settings.enabled()||settings.regenFraction()<=0) return;
        for(var active:new ArrayList<>(MythicBukkit.inst().getMobManager().getActiveMobs())) {
            try {
                if(active==null||!active.getType().getInternalName().startsWith("CA_Wild_")||active.getEntity()==null) continue;
                if(!(active.getEntity().getBukkitEntity() instanceof LivingEntity e)||!e.isValid()||e.isDead()) continue;
                if(!e.hasAI()||e.isInvulnerable()) continue;
                Long last=lastCombat.get(e.getUniqueId());
                if(last!=null&&now-last<settings.outOfCombatMs()) continue;
                var max=e.getAttribute(Attribute.MAX_HEALTH);
                if(max==null) continue;
                double cap=max.getValue();
                if(e.getHealth()>=cap) { if(last!=null) lastCombat.remove(e.getUniqueId()); continue; }
                e.setHealth(Math.min(cap,e.getHealth()+cap*settings.regenFraction()));
                regenTicks++;
            } catch(RuntimeException|LinkageError error) { plugin.warnOnce("야생 몹 회복 중 오류",error); }
        }
        if(lastCombat.size()>4096) lastCombat.values().removeIf(t->now-t>60000);
        if(lastSkill.size()>4096) lastSkill.values().removeIf(t->now-t>60000);
    }

    String diagnostic() {
        var s=settings;
        return " 전투 보정 "+(s.enabled()?"켜짐":"꺼짐")+" · 원거리 기준 "+(int)s.farDistance()+"칸 · 추적 최대 "+(int)s.chaseMax()+"칸 · 원거리 스킬 표 "+ranged.size()+"종"
            +" · 비전투 "+s.outOfCombatMs()/1000+"초 뒤 2초마다 "+Math.round(s.regenFraction()*100)+"% 회복 · 추적="+chased+" 원거리 시전="+skillCasts+" 회복="+regenTicks+" · 추적 늘림 중="+raised.size()+" 추적 복구="+followRestored;
    }
}
