package school.magiccodex.party;
import java.nio.file.*;import java.io.*;import java.util.*;
/** Derived display cache, never a nickname/ownership authority. Only accepted server identities populate it. DB worker owns IO. */
final class RankingNameCache {
 private final Path file;private Map<UUID,String> names;
 RankingNameCache(Path file)throws IOException{this.file=file;var data=new Properties();if(Files.exists(file))try(var in=Files.newInputStream(file)){data.load(in);}var copy=new HashMap<UUID,String>();try{for(var key:data.stringPropertyNames()){String name=data.getProperty(key);validate(name);copy.put(UUID.fromString(key),name);}}catch(IllegalArgumentException e){throw new IOException("Invalid ranking display cache; existing file preserved",e);}names=Map.copyOf(copy);}
 static void validate(String name){if(name==null||name.isBlank()||name.length()>32||name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))throw new IllegalArgumentException("Invalid trusted nickname");}
 String name(UUID id,String fallback){return names.getOrDefault(id,fallback);}
 UUID find(String text,boolean prefix){String q=text.toLowerCase(Locale.ROOT);UUID found=null;for(var e:names.entrySet()){String n=e.getValue().toLowerCase(Locale.ROOT);if(prefix?n.startsWith(q):n.equals(q)){if(found!=null)return null;found=e.getKey();}}return found;}
 void remember(UUID id,String name)throws IOException{validate(name);if(name.equals(names.get(id)))return;var next=new HashMap<>(names);next.put(id,name);var data=new Properties();next.forEach((k,v)->data.setProperty(k.toString(),v));Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");try(var out=Files.newOutputStream(temp)){data.store(out,"Derived trusted server display names; UUID records unchanged");}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);names=Map.copyOf(next);}
}
