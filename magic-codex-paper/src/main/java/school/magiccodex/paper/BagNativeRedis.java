package school.magiccodex.paper;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.redis.RedisKeyType;
import net.william278.husksync.user.User;

/** Version-fixed RESP reader. Native getUserData consumes the key and MUST NOT be used for readback. */
final class BagNativeRedis implements AutoCloseable {
 private final Socket socket;private final InputStream in;private final OutputStream out;private final HuskSync husk;
 BagNativeRedis(HuskSync husk)throws IOException{
  this.husk=husk;var config=husk.getSettings().getRedis();var c=config.getCredentials();
  if(!config.getSentinel().getNodes().isEmpty())throw new IOException("Shared bag Redis sentinel reader is not validated");
  socket=c.isUseSsl()?SSLSocketFactory.getDefault().createSocket():new Socket();
  socket.connect(new InetSocketAddress(c.getHost(),c.getPort()),3000);socket.setSoTimeout(3000);
  in=new BufferedInputStream(socket.getInputStream());out=new BufferedOutputStream(socket.getOutputStream());
  if(!c.getPassword().isEmpty()){if(c.getUser().isEmpty())command("AUTH",c.getPassword());else command("AUTH",c.getUser(),c.getPassword());}
  if(c.getDatabase()!=0)command("SELECT",Integer.toString(c.getDatabase()));
 }
 private String key(RedisKeyType type,User user){return type.getKeyPrefix(husk.getSettings().getClusterId())+":"+user.getUuid();}
 DataSnapshot.Packed peek(User user)throws IOException{Object data=command("GET",key(RedisKeyType.LATEST_SNAPSHOT,user));if(!(data instanceof byte[] b))throw new IOException("Native Redis snapshot missing");return DataSnapshot.deserialize(husk,b);}
 void releaseOwnCheckout(User user)throws IOException{
  // Keep the installed native GET/DEL checkout policy; ACL intentionally excludes Lua/transactions.
  // Shared lease/head CAS has already committed the exact snapshot before this native handoff.
  String checkout=key(RedisKeyType.DATA_CHECKOUT,user);Object before=command("GET",checkout);
  if(before==null)return;
  byte[] own=husk.getServerName().getBytes(StandardCharsets.UTF_8);
  if(!(before instanceof byte[] value))throw new IOException("Invalid checkout readback");
  if(!java.util.Arrays.equals(value,own))return; // destination already checked out: never clear its key
  husk.getRedisManager().setUserCheckedOut(user,false);
  Object after=command("GET",checkout);
  if(after instanceof byte[] valueAfter&&java.util.Arrays.equals(valueAfter,own))throw new IOException("Native checkout release was not acknowledged");
 }
 private Object command(String...args)throws IOException{
  out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
  for(String a:args){byte[] b=a.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write(new byte[]{13,10});}out.flush();return read();
 }
 private String line()throws IOException{var b=new ByteArrayOutputStream();int ch;while((ch=in.read())!=-1){if(ch==13){if(in.read()!=10)throw new IOException("Invalid RESP");return b.toString(StandardCharsets.UTF_8);}if(b.size()>8192)throw new IOException("RESP line too large");b.write(ch);}throw new EOFException();}
 private Object read()throws IOException{int type=in.read();if(type=='+')return line();if(type=='-'){line();throw new IOException("Redis command rejected");}if(type==':')return Long.parseLong(line());if(type=='$'){int n=Integer.parseInt(line());if(n==-1)return null;if(n<0||n>64*1024*1024)throw new IOException("RESP payload size");byte[] b=in.readNBytes(n);if(b.length!=n||in.read()!=13||in.read()!=10)throw new EOFException();return b;}throw new IOException("Unexpected RESP type");}
 public void close()throws IOException{socket.close();}
}
