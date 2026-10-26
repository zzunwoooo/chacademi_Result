package school.magiccodex.party;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic replace, fail closed on corruption; invitations intentionally expire on restart. */
final class PartyStore {
    private final Path file;
    PartyStore(Path file){this.file=file;}
    PartyBook load(int capacity)throws IOException{
        var b=new PartyBook(capacity);if(!Files.exists(file))return b;
        if(Files.size(file)>16*1024*1024)throw new IOException("Party state exceeds limit");
        try{
            var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(root.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unknown version");
            for(var value:root.getAsJsonArray("parties")){
                var p=value.getAsJsonObject();var members=new ArrayList<UUID>();for(var m:p.getAsJsonArray("members"))members.add(UUID.fromString(m.getAsString()));
                b.restore(new PartyBook.Party(UUID.fromString(p.get("id").getAsString()),UUID.fromString(p.get("leader").getAsString()),members));
            }
            return b;
        }catch(RuntimeException e){throw new IOException("Invalid party state; original file preserved",e);}
    }
    void save(PartyBook b)throws IOException{
        Files.createDirectories(file.getParent());var root=new JsonObject();root.addProperty("version",1);var parties=new JsonArray();
        for(var p:b.parties.values()){var j=new JsonObject();j.addProperty("id",p.id().toString());j.addProperty("leader",p.leader().toString());var m=new JsonArray();p.members().forEach(id->m.add(id.toString()));j.add("members",m);parties.add(j);}root.add("parties",parties);
        var tmp=Files.createTempFile(file.getParent(),"parties-",".tmp");
        try{Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(root),StandardCharsets.UTF_8);try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
}
