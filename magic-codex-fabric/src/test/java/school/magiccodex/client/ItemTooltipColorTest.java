package school.magiccodex.client;
import net.minecraft.text.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ItemTooltipColorTest {
 @Test void preservesNameAndNestedColors(){
  Text text=Text.literal("이름").styled(s->s.withColor(0x12AB34).withBold(true)).append(Text.literal("설명").styled(s->s.withColor(0xAB1234)));
  var colors=new ArrayList<Integer>();
  TooltipTextStyles.styled(text,"tooltip_name").visit((style,value)->{if(!value.isEmpty())colors.add(style.getColor().getRgb());return Optional.empty();},Style.EMPTY);
  assertEquals(List.of(0x12AB34,0xAB1234),colors);
 }
 @Test void loreColorIsNotReplacedByThemeGray(){
  Text text=Text.literal("설명").styled(s->s.withColor(0x55FFFF).withItalic(true));
  TooltipTextStyles.styled(text,"tooltip").visit((style,value)->{if(!value.isEmpty()){assertEquals(0x55FFFF,style.getColor().getRgb());assertTrue(style.isItalic());}return Optional.empty();},Style.EMPTY);
 }
}
