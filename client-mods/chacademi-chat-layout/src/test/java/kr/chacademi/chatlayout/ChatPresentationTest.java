package kr.chacademi.chatlayout;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatPresentationTest {
 @Test void stripsOnlyLeadingCategoryAndKeepsBodyAndOriginal(){
  var original=Component.literal("[전체] ").withStyle(s->s.withColor(0x55FFFF))
   .append(Component.literal("\uE114").withStyle(s->s.withFont(ResourceLocation.fromNamespaceAndPath("magiccodex","chat"))))
   .append(Component.literal(" ")).append(Component.literal("\uE110").withStyle(s->s.withFont(ResourceLocation.fromNamespaceAndPath("magiccodex","chat"))))
   .append(Component.literal(" 준우 : ")).append(Component.literal("[시스템] [귓말] &a literal").withStyle(s->s.withColor(0xFFFFFF)));
  String before=original.getString();var shown=ChatPresentation.display(original);
  assertEquals("\uE114 준우 : [시스템] [귓말] &a literal",shown.getString());assertEquals(before,original.getString());
  shown.visit((style,text)->{if(text.contains("\uE114"))assertEquals(0xFFFFFF,style.getColor().getValue());return java.util.Optional.<Void>empty();},Style.EMPTY);
 }
 @Test void preservesUnknownMessagesAndWhisperAndLeadingBody(){
  for(String text:new String[]{"hello [시스템] body","\uE101 귓말","[ItemsAdder] UPDATE available","[전체]no delimiter"}){
   var c=Component.literal(text);assertSame(c,ChatPresentation.display(c));
  }
  assertEquals("hello [시스템] body",ChatPresentation.display(Component.literal("[시스템] hello [시스템] body")).getString());
  assertEquals("\uE112 name : body",ChatPresentation.display(Component.literal("[파티] \uE112 name : body")).getString());
 }
}
