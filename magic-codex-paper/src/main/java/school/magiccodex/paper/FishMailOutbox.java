package school.magiccodex.paper;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;
/**
 * 낚시 보상 우편 대기열 (2026-10-21, A01 안 B). 우편으로 보낼 낚시 보상을 우편 저장 "전에" 파일 하나(레코드 하나)로 남긴다.
 * 형식은 FishCatchJournal 과 같다: tmp -> fsync -> ATOMIC_MOVE. 이 서버의 플러그인 폴더에만 있으므로 레코드는 항상 이 서버(origin) 것이다.
 * 우편 저장이 확인된 뒤에만 지운다(acknowledge). source 는 우편 source_key(UNIQUE) 그대로라 다시 보내도 우편이 두 통 생기지 않는다.
 */
final class FishMailOutbox {
 record Entry(String source,UUID recipient,String receipt,String title,String body,List<byte[]> items,String tankFish,String tankToken,long created,Path file){}
 private static final long MAX_FILE=1_048_576;
 private final Path root;
 FishMailOutbox(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
 static String fileName(String source){return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))+".properties";}
 synchronized Entry save(String source,UUID recipient,String receipt,String title,String body,List<byte[]> items,String tankFish,String tankToken)throws IOException{
  if(source==null||source.isBlank()||source.length()>160||recipient==null||receipt==null||!receipt.matches("[a-z0-9_]{1,64}")||items==null||items.isEmpty()||items.size()>9)throw new IOException("Invalid outbox record");
  long created=System.currentTimeMillis();var p=new Properties();
  p.setProperty("source",source);p.setProperty("recipient",recipient.toString());p.setProperty("receipt",receipt);p.setProperty("title",title);p.setProperty("body",body);
  p.setProperty("items",Integer.toString(items.size()));for(int i=0;i<items.size();i++)p.setProperty("item."+i,Base64.getEncoder().encodeToString(items.get(i)));
  p.setProperty("tankFish",tankFish==null?"":tankFish);p.setProperty("tankToken",tankToken==null?"":tankToken);p.setProperty("created",Long.toString(created));
  Path target=root.resolve(fileName(source)),tmp=Files.createTempFile(root,"outbox-",".tmp");
  try{try(var writer=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)){p.store(writer,"Pending fishing reward mail");}try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
  return new Entry(source,recipient,receipt,title,body,List.copyOf(items),p.getProperty("tankFish"),p.getProperty("tankToken"),created,target);
 }
 /** 읽을 수 있는 레코드. 깨진·심볼릭·너무 큰 파일은 건드리지 않고 broken 에 담는다(관리자 확인용, 자동 처리 안 함). */
 synchronized List<Entry> pending(List<Path> broken)throws IOException{
  var out=new ArrayList<Entry>();try(var files=Files.newDirectoryStream(root,"*.properties")){for(Path f:files){
   try{
    if(Files.isSymbolicLink(f)||Files.size(f)>MAX_FILE)throw new IOException("unsafe");
    var p=new Properties();try(var reader=Files.newBufferedReader(f,StandardCharsets.UTF_8)){p.load(reader);}
    String source=p.getProperty("source");int n=Integer.parseInt(p.getProperty("items"));if(source==null||n<1||n>9||!f.getFileName().toString().equals(fileName(source)))throw new IOException("mismatch");
    var items=new ArrayList<byte[]>();for(int i=0;i<n;i++)items.add(Base64.getDecoder().decode(Objects.requireNonNull(p.getProperty("item."+i))));
    String receipt=Objects.requireNonNull(p.getProperty("receipt"));if(!receipt.matches("[a-z0-9_]{1,64}"))throw new IOException("receipt");
    out.add(new Entry(source,UUID.fromString(p.getProperty("recipient")),receipt,Objects.requireNonNull(p.getProperty("title")),Objects.requireNonNull(p.getProperty("body")),List.copyOf(items),p.getProperty("tankFish",""),p.getProperty("tankToken",""),Long.parseLong(p.getProperty("created")),f));
   }catch(IOException|RuntimeException e){if(broken!=null)broken.add(f);}
  }}return out;
 }
 synchronized void acknowledge(Entry e)throws IOException{Path f=e.file().toAbsolutePath().normalize();if(!f.getParent().equals(root.toAbsolutePath().normalize()))throw new IOException("Unsafe outbox target");Files.deleteIfExists(f);}
}
