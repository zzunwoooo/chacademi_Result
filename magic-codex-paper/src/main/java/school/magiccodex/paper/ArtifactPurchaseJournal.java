package school.magiccodex.paper;
import java.nio.file.*;import java.nio.channels.FileChannel;import java.io.*;import java.util.*;
/** Private local receipt spool. Completed database operations are replay-safe across restarts. */
final class ArtifactPurchaseJournal {
 record Purchase(String operation,UUID player,List<ArtifactOwnershipStore.Choice> choices,String shardItem){}
 private final Path directory;
 ArtifactPurchaseJournal(Path directory)throws IOException{this.directory=directory;Files.createDirectories(directory);}
 void prepare(Purchase purchase)throws IOException{write(purchase,"prepared");}
 void paid(Purchase purchase)throws IOException{write(purchase,"paid");}
 void remove(String operation)throws IOException{Files.deleteIfExists(path(operation));}
 Map<String,Purchase> paid()throws IOException{var out=new LinkedHashMap<String,Purchase>();try(var files=Files.list(directory)){for(Path p:files.filter(x->x.toString().endsWith(".properties")).toList()){var v=new Properties();try(var in=Files.newInputStream(p)){v.load(in);}if(!"paid".equals(v.getProperty("state")))continue;var choices=new ArrayList<ArtifactOwnershipStore.Choice>();for(String row:v.getProperty("choices").split(";"))choices.add(choice(row.split("/",2)[0]));/* 천장(#98 폐기) 때 영수증의 "/대체결과"·pityGrade·pityCount 는 읽지 않고 원래 굴린 결과로 처리 */var purchase=new Purchase(v.getProperty("operation"),UUID.fromString(v.getProperty("player")),List.copyOf(choices),v.getProperty("shardItem"));out.put(purchase.operation(),purchase);}}return out;}
 long uncertain()throws IOException{try(var files=Files.list(directory)){long n=0;for(Path p:files.filter(x->x.toString().endsWith(".properties")).toList()){var v=new Properties();try(var in=Files.newInputStream(p)){v.load(in);}if(!"paid".equals(v.getProperty("state")))n++;}return n;}}
 private static ArtifactOwnershipStore.Choice choice(String row){var a=row.split(",");return new ArtifactOwnershipStore.Choice(a[0],Integer.parseInt(a[1]),Integer.parseInt(a[2]),a.length>3&&Boolean.parseBoolean(a[3]));}
 private static String text(ArtifactOwnershipStore.Choice c){return c.id()+","+c.grade()+","+c.shards()+","+c.temporaryAccess();}
 private Path path(String op){if(!op.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();return directory.resolve(op+".properties");}
 private void write(Purchase p,String state)throws IOException{var v=new Properties();v.setProperty("state",state);v.setProperty("operation",p.operation());v.setProperty("player",p.player().toString());v.setProperty("shardItem",p.shardItem());v.setProperty("choices",String.join(";",p.choices().stream().map(ArtifactPurchaseJournal::text).toList()));Path target=path(p.operation()),tmp=target.resolveSibling(target.getFileName()+".tmp");try(var out=Files.newOutputStream(tmp)){v.store(out,"Private artifact purchase receipt; never publish");}try(var channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
