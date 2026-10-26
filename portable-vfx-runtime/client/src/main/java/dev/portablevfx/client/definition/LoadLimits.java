package dev.portablevfx.client.definition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Operator-tunable limits with hard safety bounds; pack metadata cannot increase them.
 * The "rgba" byte limits count actual GPU texture bytes: BC7-compressed textures cost 1 byte/pixel, RGBA8 4.
 */
public record LoadLimits(int maxPacks, long encodedBytes, long residentRgbaBytes,
                         long prewarmRgbaBytes, int residentSystems, int prewarmSystems) {
    public static final long MIB = 1024L * 1024;
    public static final LoadLimits DEFAULT = new LoadLimits(512, 256*MIB, 1024*MIB, 1024*MIB, 512, 512);
    public LoadLimits {
        if(maxPacks<1||maxPacks>2048||encodedBytes<16*MIB||encodedBytes>1024*MIB
                ||residentRgbaBytes<64*MIB||residentRgbaBytes>1024*MIB
                ||prewarmRgbaBytes<16*MIB||prewarmRgbaBytes>residentRgbaBytes
                ||residentSystems<1||residentSystems>4096||prewarmSystems<0||prewarmSystems>residentSystems)
            throw new IllegalArgumentException("VFX load limits outside supported bounds");
    }
    public static LoadLimits load(Path path) throws IOException {
        if(!Files.exists(path))return DEFAULT;
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>16384)
            throw new IOException("Load limits must be a regular properties file under 16 KiB");
        Properties p=new Properties();try(var input=Files.newInputStream(path)){p.load(input);}
        try{
            int resident=value(p,"resident-rgba-mib",(int)(DEFAULT.residentRgbaBytes/MIB));
            int systems=value(p,"resident-systems",DEFAULT.residentSystems);
            return new LoadLimits(value(p,"max-packs",DEFAULT.maxPacks),
                value(p,"encoded-cache-mib",(int)(DEFAULT.encodedBytes/MIB))*MIB,
                resident*MIB,value(p,"prewarm-rgba-mib",Math.min(resident,(int)(DEFAULT.prewarmRgbaBytes/MIB)))*MIB,
                systems,value(p,"prewarm-systems",Math.min(systems,DEFAULT.prewarmSystems)));
        }
        catch(IllegalArgumentException error){throw new IOException("Invalid VFX load limits: "+error.getMessage(),error);}
    }
    private static int value(Properties p,String key,int fallback){return Integer.parseInt(p.getProperty(key,Integer.toString(fallback)).trim());}
}
