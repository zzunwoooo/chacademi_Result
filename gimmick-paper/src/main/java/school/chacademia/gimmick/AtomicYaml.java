package school.chacademia.gimmick;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
final class AtomicYaml {
 static YamlConfiguration load(File file){var y=new YamlConfiguration();if(!file.exists())return y;try{if(file.length()>4_194_304)throw new IOException("File too large");y.load(file);return y;}catch(Exception e){throw new IllegalStateException("Cannot read "+file.getName()+"; original preserved",e);}}
 static void save(YamlConfiguration yaml,File file)throws IOException{
  Path target=file.toPath(),parent=target.toAbsolutePath().getParent();Files.createDirectories(parent);Path tmp=Files.createTempFile(parent,"gimmick-",".tmp");
  try{Files.writeString(tmp,yaml.saveToString(),StandardCharsets.UTF_8);try(var channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
 }
}
