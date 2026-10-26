package school.magiccodex.client;
import java.util.Optional;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
/** Text-only styling, independent from item registries and client state. */
final class TooltipTextStyles {
 private TooltipTextStyles(){}
 static Text styled(Text text,String requested){
  var result=Text.empty();
  text.visit((style,value)->{
   var face=style.getFont().equals(Style.DEFAULT_FONT_ID)?Identifier.of("magiccodex",requested):style.getFont();
   int color=requested.equals("tooltip_section")?0xEBD19F:0xFFFFFF;
   result.append(Text.literal(value).setStyle(style.withFont(face).withColor(style.getColor()==null?TextColor.fromRgb(color):style.getColor())));
   return Optional.empty();
  },Style.EMPTY);
  return result;
 }
}
