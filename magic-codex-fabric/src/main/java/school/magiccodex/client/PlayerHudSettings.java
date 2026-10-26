package school.magiccodex.client;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
/** Player HUD preference only; 50 is the existing size, not a Minecraft GUI-scale multiplier. */
public final class PlayerHudSettings {
 public static final int DEFAULT=50;
 private static PlayerHudSettings current;private static Path path;
 private int percent=DEFAULT;
 public int percent(){return percent;}
 public boolean visible(){return percent>0;}
 public float multiplier(){return percent/50f;}
 public void percent(int value){percent=Math.clamp(value,0,100);}
 public static PlayerHudSettings get(){if(current==null){try{path=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("magiccodex/gui-scale.properties");}catch(RuntimeException|LinkageError ignored){}current=load(path);}return current;}
 public boolean update(int value){percent(value);return path==null||save(path);}
 public static PlayerHudSettings load(Path file){var s=new PlayerHudSettings();if(file==null||!Files.isRegularFile(file))return s;var p=new Properties();try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);s.percent(Integer.parseInt(p.getProperty("player-hud-size","50").trim()));}catch(IOException|IllegalArgumentException ignored){}return s;}
 public boolean save(Path file){var p=new Properties();if(Files.isRegularFile(file))try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);}catch(IOException|IllegalArgumentException error){return false;}p.setProperty("player-hud-size",Integer.toString(percent));Path temp=null;try{Path target=file.toAbsolutePath();Files.createDirectories(target.getParent());temp=Files.createTempFile(target.getParent(),"gui-scale-",".tmp");try(var out=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(out,"Chacademi player HUD size: 0 hidden, 50 original, 100 double");}try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}return true;}catch(IOException error){return false;}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}}
}