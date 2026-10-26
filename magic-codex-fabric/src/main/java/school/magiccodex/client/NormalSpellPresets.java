package school.magiccodex.client;
import java.nio.file.*;import java.io.*;import java.util.Properties;
/** OFF stores selection only; both equipped-spell/key files remain independent and untouched. */
final class NormalSpellPresets {
 private final Path dir;private int selected=1,preferred=1,lastActive=1;private final SpellKeySettings[] prepared=new SpellKeySettings[3];private IOException saveFailure;
 NormalSpellPresets(Path dir){this.dir=dir;try{var p=new Properties();try(var in=Files.newBufferedReader(dir.resolve("normal-preset.properties"))){p.load(in);}String s=p.getProperty("selected","1");preferred="3".equals(s)||"OFF".equalsIgnoreCase(s)?NormalPresetMode.OFF:"2".equals(s)?2:1;lastActive=preferred==2?2:preferred==NormalPresetMode.OFF&&"2".equals(p.getProperty("last-active"))?2:1;}catch(IOException|IllegalArgumentException ignored){}}
 int selected(){return selected;}int preferred(){return preferred;}int editing(){return selected==NormalPresetMode.OFF?lastActive:selected;}Path path(){return path(editing());}Path path(int index){if(index!=1&&index!=2)throw new IllegalArgumentException();return dir.resolve(index==1?"key-settings.yml":"key-settings-preset2.yml");}
 void resetSession(){selected=1;java.util.Arrays.fill(prepared,null);saveFailure=null;}
 void prepare(int index)throws IOException{prepared[index]=SpellKeySettings.load(path(index)).copy();}
 IOException saveFailure(){return saveFailure;}
 SpellKeySettings load()throws IOException{return SpellKeySettings.load(path());}
 SpellKeySettings confirmed(int index)throws IOException{
  if(index<1||index>NormalPresetMode.OFF)throw new IllegalArgumentException();int backing=index==NormalPresetMode.OFF?lastActive:index;
  var settings=prepared[backing]!=null?prepared[backing].copy():SpellKeySettings.load(path(backing));java.util.Arrays.fill(prepared,null);selected=index;preferred=index;if(index!=NormalPresetMode.OFF)lastActive=index;saveFailure=null;
  try{Files.createDirectories(dir);var target=dir.resolve("normal-preset.properties");var values=new Properties();if(Files.exists(target))try(var in=Files.newBufferedReader(target)){values.load(in);}values.setProperty("selected",Integer.toString(index));values.setProperty("last-active",Integer.toString(lastActive));var tmp=Files.createTempFile(dir,"normal-preset-",".tmp");try{try(var out=Files.newBufferedWriter(tmp)){values.store(out,"Normal magic preset selection only; OFF does not delete bindings");}try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}}catch(IOException|IllegalArgumentException e){saveFailure=e instanceof IOException io?io:new IOException("Invalid preset preferences",e);}return settings;
 }
}
