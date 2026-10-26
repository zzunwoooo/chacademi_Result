package school.magiccodex.paper;
import java.io.StringReader;import java.math.BigDecimal;import java.util.*;
import net.william278.husksync.HuskSync;import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.libraries.gson.*;import net.william278.husksync.libraries.gson.stream.*;
import net.william278.husksync.libraries.nbtapi.*;import net.william278.husksync.libraries.nbtapi.iface.*;
/** Only proven hexadecimal text-color case changes are allowed; all other typed NBT stays exact. */
final class BagInventoryProof {
 static String originalInventory(DataSnapshot.Packed snapshot,HuskSync plugin){var data=JsonParser.parseString(snapshot.asJson(plugin)).getAsJsonObject().getAsJsonObject("data");var inv=data.get("husksync:inventory");if(inv==null||!inv.isJsonPrimitive()||!inv.getAsJsonPrimitive().isString())throw new IllegalStateException("Missing original inventory block");return inv.getAsString();}
 static boolean equivalent(String original,String applied){
  if(original.equals(applied))return true;
  try{ReadWriteNBT left=NBT.parseNBT(original),right=NBT.parseNBT(applied);var li=left.getCompound("items");var ri=right.getCompound("items");if(li==null||ri==null)return false;
   if(li.getListType("items")!=NBTType.NBTTagCompound||ri.getListType("items")!=NBTType.NBTTagCompound)return false;
   var a=li.getCompoundList("items");var b=ri.getCompoundList("items");if(a.size()!=b.size())return false;
   for(int i=0;i<a.size();i++){var x=a.get(i).getCompound("components");var y=b.get(i).getCompound("components");if(x==null||y==null)continue;
    if(x.getType("minecraft:custom_name")==NBTType.NBTTagString&&y.getType("minecraft:custom_name")==NBTType.NBTTagString){String u=x.getString("minecraft:custom_name"),v=y.getString("minecraft:custom_name");if(!u.equals(v)){if(!colorOnly(u,v))return false;x.setString("minecraft:custom_name","verified-color-case");y.setString("minecraft:custom_name","verified-color-case");}}
    if(x.getListType("minecraft:lore")==NBTType.NBTTagString&&y.getListType("minecraft:lore")==NBTType.NBTTagString){var u=x.getStringList("minecraft:lore");var v=y.getStringList("minecraft:lore");if(u.size()!=v.size())return false;for(int j=0;j<u.size();j++)if(!u.get(j).equals(v.get(j))){if(!colorOnly(u.get(j),v.get(j)))return false;u.set(j,"verified-color-case");v.set(j,"verified-color-case");}}
   }
   return left.toString().equals(right.toString());
  }catch(RuntimeException ex){return false;}
 }
 static boolean colorOnly(String a,String b){
  try{JsonElement first=parse(a),second=parse(b);if(!first.isJsonObject()||!second.isJsonObject())return false;var x=first.getAsJsonObject();var y=second.getAsJsonObject();JsonElement xc=x.get("color"),yc=y.get("color");if(xc==null||yc==null||!xc.isJsonPrimitive()||!yc.isJsonPrimitive()||!xc.getAsJsonPrimitive().isString()||!yc.getAsJsonPrimitive().isString())return false;String u=xc.getAsString(),v=yc.getAsString();if(!u.matches("#[0-9a-fA-F]{6}")||!v.matches("#[0-9a-fA-F]{6}")||!u.equalsIgnoreCase(v))return false;
   x.addProperty("color",u.toLowerCase(Locale.ROOT));y.addProperty("color",v.toLowerCase(Locale.ROOT));return x.toString().equals(y.toString());
  }catch(Exception ex){return false;}
 }
 private static JsonElement parse(String text)throws Exception{if(text.length()>16384)throw new IllegalArgumentException();try(JsonReader r=new JsonReader(new StringReader(text))){r.setLenient(false);JsonElement result=read(r,0);if(r.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException();return result;}}
 private static JsonElement read(JsonReader r,int depth)throws Exception{if(depth>32)throw new IllegalArgumentException();return switch(r.peek()){
  case BEGIN_OBJECT->{r.beginObject();JsonObject o=new JsonObject();while(r.hasNext()){String k=r.nextName();if(o.has(k))throw new IllegalArgumentException();o.add(k,read(r,depth+1));}r.endObject();yield o;}
  case BEGIN_ARRAY->{r.beginArray();JsonArray a=new JsonArray();while(r.hasNext())a.add(read(r,depth+1));r.endArray();yield a;}
  case STRING->new JsonPrimitive(r.nextString());case NUMBER->new JsonPrimitive(new net.william278.husksync.libraries.gson.internal.LazilyParsedNumber(r.nextString()));case BOOLEAN->new JsonPrimitive(r.nextBoolean());case NULL->{r.nextNull();yield JsonNull.INSTANCE;}default->throw new IllegalArgumentException();};}
}
