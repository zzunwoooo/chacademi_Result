package school.magiccodex.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import school.magiccodex.protocol.ManaProtocol.Snapshot;

/** Public server API. Read snapshots from any thread; mutations require the server thread. */
public final class ManaService {
    private final Map<UUID,ManaAccount> accounts=new HashMap<>();
    private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Set<UUID> dirty=new HashSet<>();
    private final NamespacedKey currentKey,maxKey,regenKey,cooldownKey,hasteKey;
    private final JavaPlugin plugin;
    ManaService(JavaPlugin plugin){this.plugin=plugin;hasteKey=new NamespacedKey(plugin,"magic_haste");currentKey=new NamespacedKey(plugin,"mana_current");maxKey=new NamespacedKey(plugin,"mana_maximum");regenKey=new NamespacedKey(plugin,"mana_regeneration");cooldownKey=new NamespacedKey(plugin,"mana_cooldowns");}
    static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Mana mutation requires server thread");}
    public Optional<Snapshot> snapshot(UUID id){return Optional.ofNullable(snapshots.get(id));}
    ManaAccount account(UUID id){main();var a=accounts.get(id);if(a==null)throw new IllegalArgumentException("Player is not loaded");return a;}
    private double value(Player p,NamespacedKey key,double fallback){Double v=p.getPersistentDataContainer().get(key,PersistentDataType.DOUBLE);return v!=null&&Double.isFinite(v)&&v>=0&&v<=1_000_000?v:fallback;}
    private double setting(String key,double fallback){double v=plugin.getConfig().getDouble(key,fallback);return Double.isFinite(v)&&v>=0&&v<=1_000_000?v:fallback;}
    void join(Player player){
        main();var id=player.getUniqueId();if(accounts.containsKey(id))return;
        double max=value(player,maxKey,setting("mana.default-maximum",100));
        var a=new ManaAccount(value(player,currentKey,max),max,value(player,regenKey,setting("mana.default-regeneration",5)));
        String saved=player.getPersistentDataContainer().get(cooldownKey,PersistentDataType.STRING);long now=System.currentTimeMillis();
        if(saved!=null&&saved.length()<32768)for(String entry:saved.split(";")){
            var parts=entry.split("=",2);if(parts.length!=2||!parts[0].matches("[a-z0-9_-]{1,64}"))continue;
            try{long until=Long.parseLong(parts[1]);if(until>now&&until-now<=86_400_000&&a.cooldowns.size()<256)a.cooldowns.put(parts[0],until);}catch(NumberFormatException ignored){}
        }
        a.baseHaste=value(player,hasteKey,setting("mana.default-haste",0));
        accounts.put(id,a);publish(id);
    }
    private final Map<UUID,Double> discoveryMaximum=new HashMap<>();
    void publish(UUID id){var snapshot=account(id).snapshot();snapshots.put(id,snapshot);dirty.add(id);
        if(!Objects.equals(discoveryMaximum.get(id),snapshot.maximum())&&DiscoveryLink.state(id,"state.mana_maximum",snapshot.maximum()))discoveryMaximum.put(id,snapshot.maximum());
    }
    public void setCurrent(UUID id,double value){ManaAccount.valid(value);account(id).setCurrent(value);publish(id);}
    /** 거꾸로 된 마나병: mana works as an overheat gauge while on (see {@link ManaAccount#heat}). Server thread. */
    void setHeatMode(UUID id,boolean on){var a=account(id);if(on==(a.heat!=null))return;a.heatMode(on);publish(id);}
    /** Overheat lock: milliseconds until magic can be used again (0 = not locked or not in heat mode). */
    long heatLockRemaining(UUID id){var a=accounts.get(id);return a==null||a.heat==null?0:Math.max(0,a.heat.lockUntil-System.currentTimeMillis());}
    boolean heatMode(UUID id){var a=accounts.get(id);return a!=null&&a.heat!=null;}
    public void add(UUID id,double amount){account(id).add(amount);publish(id);}
    public boolean tryConsume(UUID id,double amount){boolean ok=account(id).consume(amount);if(ok)publish(id);return ok;}
    public void setBaseMaximum(UUID id,double value){ManaAccount.valid(value);var a=account(id);a.baseMaximum=value;a.normalize();publish(id);}
    public void setBaseRegeneration(UUID id,double value){ManaAccount.valid(value);account(id).baseRegen=value;publish(id);}
    public void setBaseHaste(UUID id,double value){school.magiccodex.protocol.MagicHaste.valid(value);account(id).baseHaste=value;publish(id);}
    public void setHasteModifier(UUID id,String key,double value){account(id).hasteModifier(key,value);publish(id);}
    /** Replace a named equipment/buff modifier instead of stacking it on every recalculation. */
    public void setModifier(UUID id,String key,double maximumBonus,double regenerationBonus){account(id).modifier(key,maximumBonus,regenerationBonus);publish(id);}
    public void removeModifier(UUID id,String key){account(id).removeModifier(key);publish(id);}
    /** Runtime-only multiplier, including equipment bonuses; 1 removes it. Never saved as a base stat. */
    public void setRegenerationMultiplier(UUID id,String key,double multiplier){
        var a=account(id);var before=a.snapshot();a.regenerationMultiplier(key,multiplier);if(!before.equals(a.snapshot()))publish(id);
    }
    /** Remaining cooldown of a spell in milliseconds (0 when ready). Server thread. */
    public long cooldownRemaining(UUID id,String spell){main();return account(id).remaining(spell,System.currentTimeMillis());}
    /**
     * Make a spell ready again (별빛 재현, 원초의 돌 2단계). Returns true when a cooldown was cleared.
     * The client keeps its own cooldown display, so the listener pushes magiccodex:cooldown_reset to it.
     */
    public boolean clearCooldown(UUID id,String spell){
        main();
        if(spell==null||!SPELL_ID.matcher(spell).matches())return false;
        var a=account(id);boolean had=a.cooldowns.remove(spell)!=null;
        if(had){dirty.add(id);if(cooldownCleared!=null)cooldownCleared.accept(id,spell);}
        return had;
    }
    /** Compiled once: clearCooldown runs every tick per player during 큐디치 (no per-call regex compile). */
    private static final java.util.regex.Pattern SPELL_ID=java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");
    java.util.function.BiConsumer<UUID,String> cooldownCleared;
    void tick(){
        main();for(var e:accounts.entrySet()){
            var p=Bukkit.getPlayer(e.getKey());if(p==null||p.isDead())continue;
            var before=e.getValue().snapshot();e.getValue().regenerate();
            if(!before.equals(e.getValue().snapshot()))publish(e.getKey());
        }
    }
    void save(Player p){
        main();UUID id=p.getUniqueId();var a=accounts.get(id);if(a==null)return;
        var data=p.getPersistentDataContainer();data.set(currentKey,PersistentDataType.DOUBLE,a.normalSnapshot().current());
        data.set(hasteKey,PersistentDataType.DOUBLE,a.baseHaste);
        data.set(maxKey,PersistentDataType.DOUBLE,a.baseMaximum);data.set(regenKey,PersistentDataType.DOUBLE,a.baseRegen);
        long now=System.currentTimeMillis();a.cooldowns.entrySet().removeIf(e->e.getValue()<=now);
        var text=new StringBuilder();a.cooldowns.forEach((spell,until)->text.append(spell).append('=').append(until).append(';'));
        data.set(cooldownKey,PersistentDataType.STRING,text.toString());dirty.remove(id);
    }
    void saveDirty(){for(UUID id:List.copyOf(dirty)){var p=Bukkit.getPlayer(id);if(p!=null)save(p);}}
    void quit(Player p){save(p);UUID id=p.getUniqueId();accounts.remove(id);discoveryMaximum.remove(id);snapshots.remove(id);dirty.remove(id);}
    void close(){for(UUID id:List.copyOf(accounts.keySet())){var p=Bukkit.getPlayer(id);if(p!=null)save(p);}accounts.clear();snapshots.clear();dirty.clear();}
}
