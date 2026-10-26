package dev.portablevfx.client.claude;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local data only: no scripts, URLs, links or native binaries. Each folder is committed atomically. */
public final class ClaudeConfigLoader {
    public static final int MAX_JSON_BYTES=4*1024*1024, MAX_FILE_BYTES=4*1024*1024,
            MAX_TOTAL_BYTES=256*1024*1024, MAX_EFFECTS=512;
    public record Definition(String id,String asset,int durationTicks,String role) {}
    public record Result(List<Definition> definitions,Map<String,byte[]> assets,List<String> errors,List<String> warnings) {
        public Result(List<Definition> definitions,Map<String,byte[]> assets,List<String> errors){this(definitions,assets,errors,List.of());}
    }
    private ClaudeConfigLoader() {}
    public static Result load(Path root) { return load(root, dev.portablevfx.client.definition.LoadLimits.DEFAULT); }
    public static Result load(Path root, dev.portablevfx.client.definition.LoadLimits limits) {
        List<Definition> definitions=new ArrayList<>();Map<String,byte[]> assets=new TreeMap<>();List<String> errors=new ArrayList<>();List<String> warnings=new ArrayList<>();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return new Result(List.of(),Map.of(),List.of());
        try {
            if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Effects root must be a real directory");
            var folders=new ArrayList<Path>();try(var stream=Files.list(root)){stream.limit(limits.maxPacks()+1L).forEach(folders::add);}
            if(folders.size()>limits.maxPacks())throw new IOException("At most "+limits.maxPacks()+" entries allowed in effects directory");
            folders.sort(Comparator.comparing(p->p.getFileName().toString()));Set<String> names=new HashSet<>();int total=0;
            for(Path folder:folders) {
                String folderName=folder.getFileName().toString();
                try {
                    if(!folderName.matches("[A-Za-z0-9_-]{1,64}"))throw new IOException("Folder name must contain only letters, digits, _ or -");
                    if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("Effect must be a real directory");
                    String slug=folderName.toLowerCase(Locale.ROOT);
                    if(!names.add(slug))throw new IOException("Case-insensitive effect folder collision: "+slug);
                    byte[] json=readRelative(folder,"vfx.json",MAX_JSON_BYTES);
                    ClaudeEffect effect=ClaudeEffect.parse(json); // semantic validation before any resource admission
                    effect.warnings().forEach(w -> warnings.add(folderName+": "+w));
                    JsonObject source=JsonParser.parseString(new String(json,StandardCharsets.UTF_8)).getAsJsonObject();
                    long pendingBytes=0;
                    Map<String,byte[]> pending=new TreeMap<>();List<Definition> defs=new ArrayList<>();Set<String> resourcePaths=new HashSet<>();
                    for(var entry:source.getAsJsonObject("materials").entrySet()) {
                        String ref=entry.getValue().getAsJsonObject().get("texture").getAsString();
                        if(!ref.endsWith(".png"))throw new IOException("Only PNG textures are supported: "+ref);
                        String key="claude/"+slug+"/"+ref.toLowerCase(Locale.ROOT);
                        if(resourcePaths.add(ref)) {
                            if(pending.containsKey(key))throw new IOException("Case-insensitive texture collision: "+ref);
                            byte[] png=readRelative(folder,ref,MAX_FILE_BYTES);
                            dev.portablevfx.client.definition.TextureBudget.texels(key,png);
                            pendingBytes+=png.length;
                            if(total+pendingBytes>limits.encodedBytes())throw new IOException("Encoded config cache budget exceeded; pack not admitted");
                            var header=java.nio.ByteBuffer.wrap(png);
                            if(header.getInt(16)>2048 || header.getInt(20)>2048)
                                throw new IOException("Config PNG exceeds 2048 dimension");
                            pending.put(key,png);
                        }
                    }
                    if(source.has("models")&&!source.get("models").isJsonNull())for(var model:source.getAsJsonObject("models").entrySet()) {
                        String ref=model.getValue().getAsJsonObject().get("file").getAsString();
                        if(!ref.endsWith(".glb"))throw new IOException("Only GLB model dependencies are supported: "+ref);
                        String key="claude/"+slug+"/"+ref.toLowerCase(Locale.ROOT);
                        if(resourcePaths.add(ref)) {
                            if(pending.containsKey(key))throw new IOException("Case-insensitive model collision: "+ref);
                            byte[] glb=readRelative(folder,ref,16*1024*1024);pendingBytes+=glb.length;
                            if(total+pendingBytes>limits.encodedBytes())throw new IOException("Encoded config cache budget exceeded; pack not admitted");
                            pending.put(key,glb);
                        }
                    }
                    for(JsonElement value:source.getAsJsonArray("systems")) {
                        JsonObject system=value.getAsJsonObject();String id=system.get("id").getAsString();
                        if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IOException("System ID must be resource-safe: "+id);
                        String resourceId=id.toLowerCase(Locale.ROOT);
                        String path="claude/"+slug+"/"+resourceId+".vfx.json";
                        JsonObject envelope=source.deepCopy();envelope.addProperty("portableVfxSystem",id);
                        byte[] selectedJson=envelope.toString().getBytes(StandardCharsets.UTF_8);pendingBytes+=selectedJson.length;
                        if(total+pendingBytes>limits.encodedBytes())throw new IOException("Encoded config cache budget exceeded; pack not admitted");
                        if(pending.put(path,selectedJson)!=null)throw new IOException("Duplicate system ID "+id);
                        double tail=0;for(JsonElement layer:system.getAsJsonArray("layers"))tail=Math.max(tail,Math.max(layer.getAsJsonObject().getAsJsonObject("start").getAsJsonArray("lifetime").get(0).getAsDouble(),layer.getAsJsonObject().getAsJsonObject("start").getAsJsonArray("lifetime").get(1).getAsDouble()));
                        boolean loop=system.get("loop").getAsBoolean();
                        double totalSeconds=system.get("duration").getAsDouble()+tail;
                        if(!loop && totalSeconds>599.9)throw new IOException("System duration plus tail exceeds current 600-second runtime limit: "+id);
                        int ticks=loop?dev.portablevfx.protocol.VfxProtocol.MAX_DURATION_TICKS:(int)Math.ceil(totalSeconds*20)+2;
                        defs.add(new Definition("claude:"+slug+"/"+resourceId,"claude:"+path,Math.max(1,Math.min(dev.portablevfx.protocol.VfxProtocol.MAX_DURATION_TICKS,ticks)),system.get("role").getAsString()));
                    }
                    long added=pending.values().stream().mapToLong(a->a.length).sum();
                    if(added+total>limits.encodedBytes())throw new IOException("Encoded config cache budget exceeded ("+(limits.encodedBytes()/1048576)+" MiB); pack is not admitted");
                    definitions.addAll(defs);assets.putAll(pending);total+= (int)added;
                } catch(IOException|RuntimeException e) { errors.add(folderName+": "+e.getMessage()); }
            }
        } catch(IOException|RuntimeException e) {errors.add("config: "+e.getMessage());}
        return new Result(List.copyOf(definitions),Map.copyOf(assets),List.copyOf(errors),List.copyOf(warnings));
    }
    public static byte[] readRelative(Path folder,String ref,int budget)throws IOException {
        if(ref==null||ref.length()>240||!ref.matches("[A-Za-z0-9_./-]+")||ref.startsWith("/"))throw new IOException("Unsafe relative resource: "+ref);
        Path file=folder;
        for(String part:ref.split("/",-1)) {
            if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe relative component: "+ref);
            file=file.resolve(part);
            if(Files.isSymbolicLink(file))throw new IOException("Symlink resources are forbidden: "+ref);
        }
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing regular resource: "+ref);
        if(!file.toRealPath().startsWith(folder.toRealPath()))throw new IOException("Resource escapes folder: "+ref);
        try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=stream.readNBytes(budget+1);if(bytes.length>budget)throw new IOException("Resource exceeds byte budget: "+ref);return bytes;
        }
    }
}
