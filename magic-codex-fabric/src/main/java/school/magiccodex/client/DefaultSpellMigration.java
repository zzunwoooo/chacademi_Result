package school.magiccodex.client;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Updates only recognized shipped defaults. Local edits and extra files remain untouched. */
final class DefaultSpellMigration {
    static final String VERSION="spells-renumber-288-20261025";
    private static final String ROOT="/assets/magiccodex/default-spells/";
    /** Owner-edited default that only migrates through its own exact-hash path (SpellYamlLoader.migrateHolyTamingDefault). */
    private static final String UNMANAGED_ID="rainbow_collar";
    /**
     * 2026-10-25: every shipped default (index.txt) is managed: it is replaced only when the player's copy is byte-identical
     * to a shipped version (known-hashes.tsv), so player edits always stay. Before the renumber only some number ranges were.
     */
    static boolean managedHistoryName(String name){
        if(!name.matches("[0-9]{3}_[a-z0-9_]+\\.yml"))return false;
        return !name.substring(4,name.length()-4).equals(UNMANAGED_ID);
    }
    static String hash(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static byte[] resource(String name)throws IOException {
        try(var in=DefaultSpellMigration.class.getResourceAsStream(ROOT+name)){
            if(in==null)throw new IOException("Missing default resource: "+name);return in.readAllBytes();
        }
    }
    static void install(Path directory)throws IOException {
        Map<String,Set<String>> history=new HashMap<>();
        for(String line:new String(resource("known-hashes.tsv"),StandardCharsets.UTF_8).lines().toList()){
            String[] parts=line.split("\t");if(parts.length==2 && managedHistoryName(parts[0]))history.computeIfAbsent(parts[0],k->new HashSet<>()).add(parts[1]);
        }
        applyRenames(directory,parseRenames(new String(resource("renamed-defaults.tsv"),StandardCharsets.UTF_8)),allHistory(new String(resource("known-hashes.tsv"),StandardCharsets.UTF_8)));
        Map<String,byte[]> defaults=new LinkedHashMap<>();
        for(String id:new String(resource("index.txt"),StandardCharsets.UTF_8).lines().filter(s->!s.isBlank()).toList())defaults.put(id+".yml",resource(id+".yml"));
        migrate(directory,defaults,history,VERSION);
        var all=allHistory(new String(resource("known-hashes.tsv"),StandardCharsets.UTF_8));
        for(String list:List.of("removed-defaults.txt","dropped-defaults.txt"))
            removeRetired(directory,new String(resource(list),StandardCharsets.UTF_8).lines().map(String::strip).filter(l->!l.isEmpty()&&!l.startsWith("#")).toList(),all);
    }
    /** renamed-defaults.tsv: "old.yml<TAB>new.yml" per line, # comments. */
    static Map<String,String> parseRenames(String tsv){
        Map<String,String> out=new LinkedHashMap<>();
        for(String line:tsv.lines().toList()){
            String l=line.strip();if(l.isEmpty()||l.startsWith("#"))continue;
            String[] p=l.split("\t");
            if(p.length==2&&p[0].matches("[a-zA-Z0-9_-]+\\.yml")&&p[1].matches("[a-zA-Z0-9_-]+\\.yml"))out.put(p[0],p[1]);
        }
        return out;
    }
    /**
     * 2026-10-25 renumber: the spell files were renamed (number prefix). Moves each old copy to its new name so the same id
     * never exists twice (the loader rejects the whole folder on a duplicate id). Shipped copies are moved and then updated
     * by migrate(); an edited copy keeps its content under the new name. When the new name already exists, a shipped old
     * copy is deleted and an edited one is parked as "<old>.renamed" (not .yml, so it is not loaded).
     */
    static void applyRenames(Path directory,Map<String,String> renames,Map<String,Set<String>> history)throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))return;
        for(var e:renames.entrySet()){
            Path from=directory.resolve(e.getKey()),to=directory.resolve(e.getValue());
            if(!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)||Files.size(from)>65536)continue;
            if(!Files.exists(to,LinkOption.NOFOLLOW_LINKS)){Files.move(from,to);continue;}
            boolean shipped=history.getOrDefault(e.getKey(),Set.of()).contains(hash(Files.readAllBytes(from)));
            if(shipped)Files.delete(from);
            else Files.move(from,directory.resolve(e.getKey()+".renamed"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static Map<String,Set<String>> allHistory(String tsv){
        Map<String,Set<String>> out=new HashMap<>();
        for(String line:tsv.lines().toList()){String[] parts=line.strip().split("\t");if(parts.length==2)out.computeIfAbsent(parts[0],k->new HashSet<>()).add(parts[1]);}
        return out;
    }
    /** Spells the owner dropped (2026-10-20: undeveloped 168~299). Only byte-identical shipped copies are deleted; edited files stay. */
    static void removeRetired(Path directory,List<String> names,Map<String,Set<String>> history)throws IOException {
        for(String name:names){
            if(!name.matches("[a-zA-Z0-9_-]+\\.yml"))continue;
            Path file=directory.resolve(name);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)continue;
            if(history.getOrDefault(name,Set.of()).contains(hash(Files.readAllBytes(file))))Files.deleteIfExists(file);
        }
    }
    static void migrate(Path directory,Map<String,byte[]> defaults,Map<String,Set<String>> history,String version)throws IOException {
        if(Files.isSymbolicLink(directory))throw new IOException("Refusing symlink spell directory");
        Files.createDirectories(directory);
        for(var entry:defaults.entrySet()){
            String name=entry.getKey();if(!name.matches("[a-zA-Z0-9_-]+\\.yml"))throw new IOException("Invalid default filename");
            Path file=directory.resolve(name);byte[] next=entry.getValue();
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)continue;
                byte[] current=Files.readAllBytes(file);String oldHash=hash(current);
                if(oldHash.equals(hash(next))||!history.getOrDefault(name,Set.of()).contains(oldHash))continue;
                // Recognized shipped bytes are reproducible; this migration creates no backup, as requested.
            }
            atomicWrite(file,next);
        }
        atomicWrite(directory.resolve(".defaults-version"),version.getBytes(StandardCharsets.UTF_8));
    }
    private static void atomicWrite(Path destination,byte[] bytes)throws IOException {
        Path tmp=Files.createTempFile(destination.getParent(),".defaults-",".tmp");
        try{Files.write(tmp,bytes);try{Files.move(tmp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(tmp);}
    }
}
