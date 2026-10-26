package school.magiccodex.paper;
import java.io.*;import java.nio.file.*;import java.util.*;
import school.magiccodex.paper.FishTank.Fish;
/** Write-ahead records for catches only; not a copy of player data or a backup. */
final class FishCatchJournal {
 record Pending(UUID owner,Fish fish,Path file){}
 private final Path root;
 FishCatchJournal(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
 synchronized Pending save(UUID owner,Fish fish)throws IOException{
  var p=new Properties();p.setProperty("owner",owner.toString());p.setProperty("id",fish.id());p.setProperty("fish",fish.fish());p.setProperty("size",Double.toString(fish.size()));p.setProperty("caught",Long.toString(fish.caughtAt()));
  Path target=root.resolve(owner+"_"+fish.id()+".properties"),tmp=Files.createTempFile(root,"catch-",".tmp");
  try{try(var writer=Files.newBufferedWriter(tmp)){p.store(writer,"Pending tank catch");}try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
  return new Pending(owner,fish,target);
 }
 synchronized List<Pending> pending()throws IOException{
  var out=new ArrayList<Pending>();try(var files=Files.newDirectoryStream(root,"*.properties")){for(Path f:files){
   if(Files.isSymbolicLink(f)||Files.size(f)>4096)throw new IOException("Invalid catch journal file");
   var p=new Properties();try(var reader=Files.newBufferedReader(f)){p.load(reader);}
   try{var owner=UUID.fromString(p.getProperty("owner"));var id=UUID.fromString(p.getProperty("id")).toString();var fish=new Fish(id,p.getProperty("fish"),Double.parseDouble(p.getProperty("size")),Long.parseLong(p.getProperty("caught")));
    if(!f.getFileName().toString().equals(owner+"_"+id+".properties")||!Double.isFinite(fish.size())||fish.size()<=0)throw new IllegalArgumentException();out.add(new Pending(owner,fish,f));
   }catch(RuntimeException e){throw new IOException("Invalid catch journal record");}
  }}return out;
 }
 synchronized void acknowledge(Pending p)throws IOException{Path f=p.file().toAbsolutePath().normalize();if(!f.getParent().equals(root.toAbsolutePath().normalize()))throw new IOException("Unsafe catch journal target");Files.deleteIfExists(f);}
}