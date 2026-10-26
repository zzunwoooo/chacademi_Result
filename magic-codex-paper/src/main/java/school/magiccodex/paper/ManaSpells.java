package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Server-owned command/cost catalog; replacement is atomic when reload validation passes. */
final class ManaSpells {
    record Spell(String id,String command,String permission,double cost,int cooldown) {}
    final Map<String,Spell> byId,byCommand;
    final List<String> discoveryPermissions;
    final Map<String,String> aliases;
    final Map<String,Map<String,String>> registeredCatalog;
    final boolean catalogMode;
    /** Enabled spell IDs in mana-spells.yml file order (byId is a Map.copyOf and has no order). /테스트 마법 자동시전 walks this. */
    final List<String> order;
    private ManaSpells(Map<String,Spell> ids,Map<String,Spell> commands,List<String> permissions,Map<String,String> aliases,Map<String,Map<String,String>> registeredCatalog,boolean catalogMode){this.registeredCatalog=Map.copyOf(registeredCatalog);this.catalogMode=catalogMode;this.aliases=Map.copyOf(aliases);byId=Map.copyOf(ids);byCommand=Map.copyOf(commands);discoveryPermissions=List.copyOf(permissions);order=List.copyOf(ids.keySet());}
    Spell forCast(String id){return byId.get(TamingSpell.canonicalId(id));}
    String resolve(String input){String key=aliasKey(input);if(TamingSpell.matches(key))return forCast(key)==null?null:TamingSpell.ID;if(byId.containsKey(key))return key;return aliases.get(key);}
    private static String aliasKey(String input){return input.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);}
    static String normalize(String command){
        String c=command.strip().replaceFirst("^/","").replaceAll("\\s+"," ");
        int space=c.indexOf(' ');String root=space<0?c:c.substring(0,space);int colon=root.indexOf(':');
        if(colon>=0)root=root.substring(colon+1);
        return (root+(space<0?"":c.substring(space))).toLowerCase(Locale.ROOT);
    }
    static ManaSpells load(File file)throws Exception{
        var y=new YamlConfiguration();y.load(file);var root=y.getConfigurationSection("spells");
        if(root==null||root.getKeys(false).size()>school.magiccodex.protocol.PermissionProtocol.MAX_PERMISSIONS)throw new IllegalArgumentException("Expected up to "+school.magiccodex.protocol.PermissionProtocol.MAX_PERMISSIONS+" spells");
        Map<String,Spell> ids=new LinkedHashMap<>(),commands=new HashMap<>();
        Map<String,String> aliases=new HashMap<>();Set<String> ambiguous=new HashSet<>();
        Map<String,Map<String,String>> registeredCatalog=new LinkedHashMap<>();
        List<String> permissions=new ArrayList<>();
        for(String id:root.getKeys(false)){
            // This is a compatibility alias, never a second spell or a second cooldown account.
            if(TamingSpell.LEGACY_ID.equals(id))throw new IllegalArgumentException("교화는 rainbow_collar 항목으로만 등록하세요: taming은 호환 별칭입니다.");
            var s=root.getConfigurationSection(id);if(s==null)throw new IllegalArgumentException(id);
            String discovery=s.getString("permission","");
            if(discovery.matches("[a-z0-9_.-]{1,100}"))permissions.add(discovery);
            String displayName=s.getString("name",id);
            if(id.matches("[a-z0-9_-]{1,64}")&&discovery.matches("[a-z0-9_.-]{1,100}")&&!displayName.isBlank()&&displayName.length()<=100)
                registeredCatalog.put(id,Map.of("name",displayName,"permission",discovery));
            if(!s.getBoolean("enabled",true))continue;
            String command=s.getString("command","").strip(),permission=s.getString("permission","");
            if(!id.matches("[a-z0-9_-]{1,64}")||command.isBlank()||command.length()>256||command.startsWith("/")
                    ||command.chars().anyMatch(c->c<32)||!permission.matches("[a-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid spell: "+id);
            Object rawCost=s.get("mana-cost"),rawCooldown=s.get("cooldown-seconds");
            if(!(rawCost instanceof Number cost)||!(rawCooldown instanceof Number cd))throw new IllegalArgumentException("Numeric cost/cooldown required: "+id);
            ManaAccount.valid(cost.doubleValue());double seconds=cd.doubleValue();
            if(!Double.isFinite(seconds)||seconds<0||seconds>86400)throw new IllegalArgumentException("Invalid cooldown: "+id);
            var spell=new Spell(id,command,permission,cost.doubleValue(),(int)Math.round(seconds*1000));
            if(ids.put(id,spell)!=null||commands.put(normalize(command),spell)!=null)throw new IllegalArgumentException("Duplicate command: "+id);
            var names=new ArrayList<String>(s.getStringList("aliases"));names.add(displayName);
            for(String name:names){String key=aliasKey(name);if(key.isEmpty())continue;String previous=aliases.putIfAbsent(key,id);if(previous!=null&&!previous.equals(id))ambiguous.add(key);}
        }
        ambiguous.forEach(aliases::remove);
        return new ManaSpells(ids,commands,permissions,aliases,registeredCatalog,y.getBoolean("catalog-mode",false));
    }
}
