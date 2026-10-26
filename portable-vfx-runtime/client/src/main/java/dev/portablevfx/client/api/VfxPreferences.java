package dev.portablevfx.client.api;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
/** Local draw multiplier only; existing defaults are unchanged at 100%/100%. */
public final class VfxPreferences {
    private static VfxPreferences current; private float own=1,other=1;
    public static VfxPreferences get(){if(current==null)current=load(path());return current;}
    private static Path path(){return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("magiccodex/vfx.properties");}
    public float opacity(boolean mine){return mine?own:other;}
    public static float[] values(){var p=get();return new float[]{p.own,p.other};}
    public static void save(float own,float other)throws IOException{var next=new VfxPreferences();next.own=bounded(own);next.other=bounded(other);next.save(path());current=next;}
    /** Global effect-only bloom strength (0..1, default 0.4); applies immediately. Used by the MagicCodex settings screen. */
    public static float bloom(){return (float)dev.portablevfx.client.claude.ClaudeBloomConfig.strength();}
    public static void saveBloom(float value)throws IOException{dev.portablevfx.client.claude.ClaudeBloomConfig.save(value);}
    public static float bounded(float value){return Float.isFinite(value)?Math.clamp(value,0,1):1;}
    public static VfxPreferences load(Path path){
        var result=new VfxPreferences();var p=new Properties();
        if(!Files.isRegularFile(path))return result;
        try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(reader);result.own=parse(p.getProperty("own"));result.other=parse(p.getProperty("other"));}catch(IOException|IllegalArgumentException ignored){}
        return result;
    }
    private static float parse(String value){try{return bounded(value==null?1:Float.parseFloat(value));}catch(NumberFormatException e){return 1;}}
    public void save(Path path)throws IOException{
        var p=new Properties();if(Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(reader);}
        p.setProperty("own",Float.toString(own));p.setProperty("other",Float.toString(other));
        Path target=path.toAbsolutePath();Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"vfx-",".tmp");
        try{try(var writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(writer,"Local skill VFX opacity; no gameplay changes");}Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
}
