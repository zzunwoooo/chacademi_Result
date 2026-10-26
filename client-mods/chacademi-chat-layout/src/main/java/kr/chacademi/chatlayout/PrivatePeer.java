package kr.chacademi.chatlayout;
import java.util.*;
import java.nio.charset.StandardCharsets;
/** Persistent recipient identity: account/server scope plus server-authenticated UUID. */
public record PrivatePeer(String scope,UUID id){
 public static final String PREFIX="/chacademi-private ";
 public String marker(){return PREFIX+id+" "+Base64.getUrlEncoder().withoutPadding().encodeToString(scope.getBytes(StandardCharsets.UTF_8))+" ";}
 public static PrivatePeer parse(String marker){
  try{
   if(marker==null||!marker.startsWith(PREFIX))return null;
   String[] p=marker.substring(PREFIX.length()).strip().split(" ");
   if(p.length!=2)return null;
   return new PrivatePeer(new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8),UUID.fromString(p[0]));
  }catch(IllegalArgumentException e){return null;}
 }
 public static String message(String value){
  if(value==null||value.isBlank()||value.length()>240||value.codePoints().anyMatch(c->Character.isISOControl(c)||c==0xA7||Character.getType(c)==Character.FORMAT))
   throw new IllegalArgumentException("전언은 1~240자의 일반 글자로 입력해 주세요.");
  return value.strip();
 }
}