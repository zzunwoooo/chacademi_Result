package kr.chacademi.chatlayout;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
/** Display-only projection. ChatPlus history and channel matching retain the original component. */
public final class ChatPresentation {
 private ChatPresentation(){}
 private static final ResourceLocation CHAT=ResourceLocation.fromNamespaceAndPath("magiccodex","chat");
 public static int prefixLength(String text){
  for(String tag:new String[]{"[전체] ","[기숙사] ","[파티] ","[시스템] "})if(text.startsWith(tag))return tag.length();
  return 0;
 }
 public static MutableComponent display(MutableComponent original){
  String text=original.getString();int skip=prefixLength(text);if(skip==0)return original;
  // Only remove a category prefix at the start, never a user's body text.
  // Network relay has server + channel icons; the tab already conveys channel, retain server only.
  int duplicateStart=-1,duplicateEnd=-1;
  if((text.startsWith("[전체] ")||text.startsWith("[기숙사] "))&&text.length()>skip+2
    &&(text.charAt(skip)=='\uE114'||text.charAt(skip)=='\uE115')&&text.charAt(skip+1)==' '
    &&(text.charAt(skip+2)=='\uE110'||text.charAt(skip+2)=='\uE111')){duplicateStart=skip+1;duplicateEnd=skip+3;}
  final int dropStart=duplicateStart,dropEnd=duplicateEnd;int[] offset={0};MutableComponent result=Component.empty();
  original.visit((style,part)->{
   int start=offset[0];offset[0]+=part.length();StringBuilder keep=new StringBuilder();
   for(int i=0;i<part.length();i++){int at=start+i;if(at>=skip&&!(at>=dropStart&&at<dropEnd))keep.append(part.charAt(i));}
   if(!keep.isEmpty()){
    Style visual=style;
    if(CHAT.equals(style.getFont()))visual=style.withColor(0xFFFFFF).withBold(false).withItalic(false);
    result.append(Component.literal(keep.toString()).setStyle(visual));
   }
   return Optional.<Void>empty();
  },Style.EMPTY);
  return result;
 }
}
