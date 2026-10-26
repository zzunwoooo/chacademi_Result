package school.magiccodex.paper;
import java.io.*;import java.nio.file.*;import java.util.zip.GZIPInputStream;
/** Read-only acknowledgement of the unique receipt in the same saved player snapshot as the item change. */
final class DeliveryReceipt {
 static boolean saved(Path file,String receipt)throws IOException{
  if(!Files.isRegularFile(file))return false;if(Files.size(file)>4_194_304)throw new IOException("player data size");
  try(var raw=Files.newInputStream(file);var gzip=new GZIPInputStream(raw);var in=new DataInputStream(new Limited(gzip))){int type=in.readUnsignedByte();if(type!=10)throw new IOException("player root");in.readUTF();return tag(in,type,"",receipt,0);}
 }
 private static int count(DataInputStream in,int unit)throws IOException{int n=in.readInt();if(n<0||n>4_194_304/Math.max(1,unit))throw new IOException("NBT length");return n;}
 private static boolean tag(DataInputStream in,int type,String name,String receipt,int depth)throws IOException{
  if(depth>64)throw new IOException("NBT depth");switch(type){
   case 0:return false;case 1:{byte value=in.readByte();return name.equals(receipt)&&value==1;}
   case 2:in.readShort();break;case 3:in.readInt();break;case 4:in.readLong();break;case 5:in.readFloat();break;case 6:in.readDouble();break;
   case 7:in.skipNBytes(count(in,1));break;case 8:in.readUTF();break;
   case 9:{int subtype=in.readUnsignedByte(),n=count(in,1);boolean found=false;for(int i=0;i<n;i++)found|=tag(in,subtype,"",receipt,depth+1);return found;}
   case 10:{boolean found=false;while(true){int next=in.readUnsignedByte();if(next==0)return found;String key=in.readUTF();found|=tag(in,next,key,receipt,depth+1);}}
   case 11:in.skipNBytes((long)count(in,4)*4);break;case 12:in.skipNBytes((long)count(in,8)*8);break;
   default:throw new IOException("NBT type");
  }return false;
 }
 private static final class Limited extends FilterInputStream {
  private int left=4_194_304;Limited(InputStream in){super(in);}
  @Override public int read()throws IOException{if(left--<=0)throw new IOException("NBT size");return super.read();}
  @Override public int read(byte[] b,int off,int len)throws IOException{if(left<=0)throw new IOException("NBT size");int n=super.read(b,off,Math.min(len,left));if(n>0)left-=n;return n;}
  @Override public long skip(long n)throws IOException{if(left<=0)throw new IOException("NBT size");long read=super.skip(Math.min(n,left));left-=Math.toIntExact(read);return read;}
 }
}
