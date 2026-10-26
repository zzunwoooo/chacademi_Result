package school.magiccodex.client;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** One atomic generated-cache slot. Never touches comparison output or user files. IO thread only. */
final class PortraitCache {
    record Entry(String sha,byte[] png){}
    static Entry read(Path dir,int limit)throws IOException {
        Path file=dir.resolve("portrait.cache");
        if(!Files.isRegularFile(file)||Files.size(file)<65||Files.size(file)>limit+65L)return null;
        byte[] data=Files.readAllBytes(file);
        if(data[64]!='\n')return null;
        return new Entry(new String(data,0,64,StandardCharsets.US_ASCII),Arrays.copyOfRange(data,65,data.length));
    }
    static void write(Path dir,String sha,byte[] png,BooleanSupplier current)throws IOException {
        if(!current.getAsBoolean())return;
        Files.createDirectories(dir);Path tmp=Files.createTempFile(dir,"portrait-",".tmp");
        try {
            try(var out=Files.newOutputStream(tmp)){out.write(sha.getBytes(StandardCharsets.US_ASCII));out.write('\n');out.write(png);}
            if(!current.getAsBoolean())return;
            Files.move(tmp,dir.resolve("portrait.cache"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            // Only legacy files created by this cache are retired after successful atomic replacement.
            Files.deleteIfExists(dir.resolve("portrait.png"));Files.deleteIfExists(dir.resolve("portrait.sha"));
        } finally {Files.deleteIfExists(tmp);}
    }
}
