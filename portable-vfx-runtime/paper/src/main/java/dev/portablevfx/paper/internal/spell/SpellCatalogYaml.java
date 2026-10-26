package dev.portablevfx.paper.internal.spell;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Adapter for Bukkit YAML; domain validation remains in SpellCatalog. */
public final class SpellCatalogYaml {
    private SpellCatalogYaml() {}
    public static SpellCatalog read(ConfigurationSection root) {
        return SpellCatalog.fromMap(convert(root));
    }
    /** 마법별 검증: 잘못된 마법만 건너뛰고 problems 에 사유를 남긴다. 파일 구조 오류는 예외. */
    public static SpellCatalog readLenient(ConfigurationSection root,Map<String,String> problems) {
        return SpellCatalog.fromMapLenient(convert(root),problems);
    }
    private static Map<String,Object> convert(ConfigurationSection section) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:section.getKeys(false))out.put(key,convertValue(section.get(key)));
        return out;
    }
    private static Object convertValue(Object value) {
        if(value instanceof ConfigurationSection section)return convert(section);
        if(value instanceof List<?> list)return list.stream().map(SpellCatalogYaml::convertValue).toList();
        if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(String.valueOf(k),convertValue(v)));return out;}
        return value;
    }
}
