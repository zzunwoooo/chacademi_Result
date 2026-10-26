package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.io.*;import java.util.*;import school.magiccodex.protocol.ArtifactProtocol;import school.magiccodex.protocol.ArtifactProtocol.*;
class ArtifactProtocolTest {
 static List<Entry> catalog(int n){var l=new ArrayList<Entry>();for(int i=0;i<n;i++)l.add(new Entry("artifact_"+i,"아티팩트 "+i,i%4,"설명 ".repeat(30),"효과 ".repeat(100),"magiccodex:textures/artifact/a.png",i%3==0?ArtifactProtocol.PASSIVE:i%60));return l;}
 @Test void catalogueWithCooldownsRoundTripsInParts(){
  var in=catalog(200);var out=new ArrayList<Entry>();var parts=ArtifactProtocol.catalog(7,in);assertTrue(parts.size()>1);
  for(byte[] p:parts){assertTrue(p.length<=ArtifactProtocol.MAX_BYTES);out.addAll(((Catalog)ArtifactProtocol.decode(p)).entries());}
  assertEquals(in,out);assertFalse(out.get(0).usable());assertTrue(out.get(1).usable());}
 @Test void stateCarriesRemainingCooldownPerEquippedId(){
  var s=(State)ArtifactProtocol.decode(ArtifactProtocol.encode(new State(9,2,new boolean[]{true,false,true},List.of("a","b"),"사용했습니다.",new int[]{0,4500})));
  assertEquals(List.of("a","b"),s.equipped());assertArrayEquals(new int[]{0,4500},s.cooldownMs());
  assertThrows(IllegalArgumentException.class,()->new State(1,1,new boolean[0],List.of("a"),"",new int[0]));}
 @Test void useRequestAndVersionOneFramesStillDecode() throws IOException{
  var r=ArtifactProtocol.request(ArtifactProtocol.request(new Request(ArtifactProtocol.USE,5,"snack_box")));assertEquals(ArtifactProtocol.USE,r.action());
  var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);
  d.writeInt(1);d.writeByte(ArtifactProtocol.CATALOG);d.writeLong(3);d.writeShort(0);d.writeShort(1);d.writeShort(1);
  d.writeUTF("old");d.writeUTF("옛것");d.writeByte(0);d.writeUTF("");d.writeUTF("");d.writeUTF("");
  var c=(Catalog)ArtifactProtocol.decode(b.toByteArray());assertFalse(c.entries().get(0).usable());
  b=new ByteArrayOutputStream();d=new DataOutputStream(b);
  d.writeInt(1);d.writeByte(ArtifactProtocol.STATE);d.writeLong(3);d.writeByte(1);d.writeShort(1);d.write(new byte[]{1});d.writeByte(1);d.writeUTF("old");d.writeUTF("");
  assertArrayEquals(new int[]{0},((State)ArtifactProtocol.decode(b.toByteArray())).cooldownMs());
  assertThrows(IllegalArgumentException.class,()->new Request(7,0,"x"));
  assertThrows(IllegalArgumentException.class,()->new Entry("x","x",0,"","","",ArtifactProtocol.MAX_COOLDOWN+1));}
 @Test void summonFramesRoundTrip(){
  var m=new Summon(ArtifactProtocol.SUMMON_RESULT,3,"rewind_hourglass",3,true,0,new int[]{0,1,2,0},"");
  var back=(Summon)ArtifactProtocol.decode(ArtifactProtocol.encode(m));
  assertEquals("rewind_hourglass",back.artifactId());assertEquals(3,back.grade());assertTrue(back.isNew());assertArrayEquals(new int[]{0,1,2,0},back.decoys());
  var info=(Summon)ArtifactProtocol.decode(ArtifactProtocol.encode(Summon.simple(ArtifactProtocol.SUMMON_STATE,7,"")));assertEquals(7,info.keys());
  assertEquals(ArtifactProtocol.SUMMON,ArtifactProtocol.request(ArtifactProtocol.request(new Request(ArtifactProtocol.SUMMON,0,""))).action());
  assertThrows(IllegalArgumentException.class,()->new Summon(ArtifactProtocol.SUMMON_RESULT,0,"x",0,false,0,new int[]{0,0,4,0},""));
  assertThrows(IllegalArgumentException.class,()->new Request(ArtifactProtocol.USE,0,""));}
 @Test void fivePullRoundTrips(){
  var pulls=List.of(new Pull("sturdy_vest",0,true,0),new Pull("sturdy_vest",0,false,1),new Pull("mana_necklace",1,true,0),new Pull("giant_heart",2,true,0),new Pull("rewind_hourglass",3,true,0));
  var back=(SummonMulti)ArtifactProtocol.decode(ArtifactProtocol.encode(new SummonMulti(4,pulls,"조각 1개")));
  assertEquals(pulls,back.pulls());assertEquals(4,back.keys());assertEquals("조각 1개",back.notice());
  assertEquals(ArtifactProtocol.SUMMON_MULTI,ArtifactProtocol.request(ArtifactProtocol.request(new Request(ArtifactProtocol.SUMMON_MULTI,0,""))).action());
  assertThrows(IllegalArgumentException.class,()->new SummonMulti(0,List.of(),""));
  assertThrows(IllegalArgumentException.class,()->new Pull("x",4,true,0));}
}
