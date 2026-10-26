package school.magiccodex.paper;
import java.util.UUID;
import org.bukkit.Bukkit;
/** Optional server-side integration; no plugin dependency or client-trusted cast claims. */
final class DiscoveryLink {
    static boolean signal(UUID player,String key,double amount){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled())return false;
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);return service!=null&&Boolean.TRUE.equals(type.getMethod("signal",UUID.class,String.class,double.class).invoke(service,player,key,amount));}
        catch(ReflectiveOperationException|RuntimeException e){Bukkit.getLogger().warning("MagicDiscovery friend event failed: "+e.getClass().getSimpleName());return false;}
    }
    @SuppressWarnings("unchecked") static java.util.Map<String,String> registeredSpells(){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled())return java.util.Map.of();
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);return service==null?java.util.Map.of():java.util.Map.copyOf((java.util.Map<String,String>)type.getMethod("registeredSpells").invoke(service));}
        catch(ReflectiveOperationException|RuntimeException e){return java.util.Map.of();}
    }
    static void adminSetLearned(UUID player,String spell,boolean learned,java.util.function.Consumer<String> done){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled()){done.accept("마법 발견 플러그인이 준비되지 않았습니다.");return;}
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);if(service==null){done.accept("마법 발견 서비스가 준비되지 않았습니다.");return;}type.getMethod("adminSetLearned",UUID.class,String.class,boolean.class,java.util.function.Consumer.class).invoke(service,player,spell,learned,done);}
        catch(ReflectiveOperationException|RuntimeException e){Bukkit.getLogger().warning("MagicDiscovery admin update failed: "+e.getClass().getSimpleName());done.accept("마법 습득 상태를 저장하지 못했습니다. 발견 플러그인 버전을 확인해 주세요.");}
    }
    static boolean state(UUID player,String key,double value){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled())return false;
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);return service!=null&&Boolean.TRUE.equals(type.getMethod("state",UUID.class,String.class,double.class).invoke(service,player,key,value));}
        catch(ReflectiveOperationException|RuntimeException error){return false;}
    }
    @SuppressWarnings("unchecked") static java.util.Map<String,String> manualConditions(){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled())return java.util.Map.of();
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);return service==null?java.util.Map.of():java.util.Map.copyOf((java.util.Map<String,String>)type.getMethod("manualConditions").invoke(service));}
        catch(ReflectiveOperationException|RuntimeException error){return java.util.Map.of();}
    }
    static void adminCondition(UUID player,String spell,String action,java.util.function.Consumer<String> done){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled()){done.accept("발견 플러그인이 준비되지 않았습니다.");return;}
        try{Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);if(service==null){done.accept("발견 서비스가 준비되지 않았습니다.");return;}type.getMethod("adminCondition",UUID.class,String.class,String.class,java.util.function.Consumer.class).invoke(service,player,spell,action,done);}
        catch(ReflectiveOperationException|RuntimeException error){done.accept("수동 조건 처리를 완료하지 못했습니다. 발견 플러그인 버전을 확인하세요.");}
    }
    /** 마법 힌트권 3성: 조건 키 → 지금 값. done 은 메인 스레드에서 정확히 한 번 (null = 발견 플러그인 없음·옛 버전·읽기 실패). */
    @SuppressWarnings("unchecked") static void hintProgress(UUID player,String spell,java.util.function.Consumer<java.util.Map<String,Double>> done){
        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled()){done.accept(null);return;}
        try{
            Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());Object service=Bukkit.getServicesManager().load(type);
            if(service==null){done.accept(null);return;}
            java.util.function.Consumer<java.util.Map<String,Double>> once=new java.util.function.Consumer<>(){boolean sent;public void accept(java.util.Map<String,Double> v){if(sent)return;sent=true;done.accept(v);}};
            try{type.getMethod("hintProgress",UUID.class,String.class,java.util.function.Consumer.class).invoke(service,player,spell,once);}
            catch(ReflectiveOperationException|RuntimeException e){once.accept(null);}
        }catch(ReflectiveOperationException|RuntimeException e){done.accept(null);}
    }
    private static Class<?> serviceClass;private static java.lang.reflect.Method cast;
    static void cast(UUID player,String spell,double spent){
        if("light_leap".equals(spell)){
            var actor=Bukkit.getPlayer(player);var climate=Bukkit.getServicesManager().load(ClimateService.class);
            if(actor!=null&&climate!=null&&climate.enabledIn(actor.getWorld())&&climate.season()==ClimateService.Season.SUMMER){
                var registry=io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.BIOME);
                var key=io.papermc.paper.registry.TypedKey.create(io.papermc.paper.registry.RegistryKey.BIOME,actor.getLocation().getBlock().getBiome().getKey().toString());
                if(registry.getTag(io.papermc.paper.registry.keys.tags.BiomeTagKeys.IS_OCEAN).contains(key))signal(player,"cast.updraft_summer_ocean",1);
            }
        }

        var plugin=Bukkit.getPluginManager().getPlugin("MagicDiscovery");if(plugin==null||!plugin.isEnabled())return;
        try{
            Class<?> type=Class.forName("school.magiccodex.discovery.DiscoveryService",false,plugin.getClass().getClassLoader());
            if(type!=serviceClass){serviceClass=type;cast=type.getMethod("cast",UUID.class,String.class,double.class);}
            Object service=Bukkit.getServicesManager().load(type);if(service!=null)cast.invoke(service,player,spell,spent);
        }catch(ReflectiveOperationException|RuntimeException e){Bukkit.getLogger().warning("MagicDiscovery cast event failed: "+e.getClass().getSimpleName());}
    }
}
