package school.magiccodex.discovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Admin knowledge uses the current cast catalog without inventing natural discovery conditions. */
final class AdminSpellCatalog {
    record Entry(String id,String name,String permission) {
        static Entry from(Definitions.Spell spell){return new Entry(spell.id(),spell.name(),spell.permission());}
    }
    private Map<String,Entry> entries=Map.of(),permissionEntries=Map.of();

    /** A null Bridge catalog means standalone compatibility; an empty provided catalog is authoritative. */
    void replace(Map<String,Definitions.Spell> discoveries,Map<?,?> registeredCasts){
        var natural=new LinkedHashMap<String,Entry>();
        discoveries.forEach((id,spell)->natural.put(id,Entry.from(spell)));
        var admins=new LinkedHashMap<String,Entry>();
        if(registeredCasts==null)admins.putAll(natural);
        else registeredCasts.forEach((rawId,rawFields)->{
            if(!(rawId instanceof String id)||!(rawFields instanceof Map<?,?> fields)
                    ||!(fields.get("name") instanceof String name)||!(fields.get("permission") instanceof String permission)
                    ||!id.matches("[a-z0-9_-]{1,64}")||name.isBlank()||name.length()>100
                    ||!permission.matches("[a-z0-9_.-]{1,100}"))return;
            admins.put(id,new Entry(id,name,permission));
        });
        // Legacy discovery IDs remain available for restoring historical knowledge, never guessed aliases.
        natural.putAll(admins);permissionEntries=Collections.unmodifiableMap(natural);
        entries=Collections.unmodifiableMap(admins);
    }

    Map<String,Entry> permissionEntries(){return permissionEntries;}
    Entry get(String id){return entries.get(id);}
    Map<String,String> names(){
        var names=new LinkedHashMap<String,String>();entries.forEach((id,entry)->names.put(id,entry.name()));
        return Collections.unmodifiableMap(names);
    }
}
