package school.magiccodex.client;

import java.util.*;
import net.minecraft.text.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TooltipRarityTest {
    private MutableText text(String value){return Text.literal(value);}
    @Test void recognizesOnlyExactFourNames(){
        for(var grade:TooltipRarity.Grade.values())
            assertEquals(grade,TooltipRarity.recognize(text(grade.label)));
    }
    @Test void stripsLegacyFormattingAndOuterWhitespace(){
        assertEquals(TooltipRarity.Grade.HIGH,TooltipRarity.recognize(text(" \t\u00a7b\uACE0\uAE09\u00a7r\n")));
        Text nested=text(" \uC720").append(text("\uC77C").styled(s->s.withColor(0xFFFFFF).withBold(true))).append(" ");
        assertEquals(TooltipRarity.Grade.UNIQUE,TooltipRarity.recognize(nested));
    }
    @Test void missingBlankSentencesAndInnerSpacesRemainOrdinary(){
        for(String value:List.of(""," ","\uC804\uC124 \uC544\uC774\uD15C","\uACE0 \uAE09","[ \uC720\uC77C ]","Rare","\uC77C\uBC18.")){
            assertNull(TooltipRarity.recognize(text(value)),value);
        }
        assertNull(TooltipRarity.recognize(null));
    }
    @Test void matchesFullStyledLoreAndPreservesTypeIdentity(){
        Text type=text("type").styled(s->s.withColor(0x123456).withItalic(true));
        Text grade=text("\uC804\uC124").styled(s->s.withColor(0xFEDCBA));
        Text description=text("description").append(text(" rich").styled(s->s.withBold(true)));
        List<Text> lore=List.of(type,grade,description);
        List<Text> tooltip=List.of(text("enchanted"),text("\uACE0\uAE09"),type,grade,description,text("attribute"));
        var result=TooltipRarity.find(tooltip,lore);
        assertEquals(2,result.index());assertSame(type,result.type());
        assertEquals(TooltipRarity.Grade.LEGENDARY,result.grade());
        assertEquals(6,tooltip.size());assertSame(description,lore.get(2));
    }
    @Test void arbitrarySecondTooltipLineIsNeverRarity(){
        Text type=text("type"),desc=text("ordinary");
        var result=TooltipRarity.find(List.of(text("\uC804\uC124"),type,desc),List.of(type,desc));
        assertNull(result.grade());
        assertNull(TooltipRarity.find(List.of(text("type"),text("\uACE0\uAE09")),List.of()).grade());
        assertNull(TooltipRarity.find(List.of(type),List.of(type)).grade());
    }
    @Test void sameStringsWithDifferentStylesDoNotMatch(){
        Text type=text("type"),grade=text("\uACE0\uAE09");
        var result=TooltipRarity.find(List.of(type,grade),List.of(type.copy().styled(s->s.withItalic(true)),grade));
        assertEquals(-1,result.index());assertNull(result.type());assertNull(result.grade());
    }
    @Test void missingPartOfLoreDoesNotRemoveEnchantOrAttribute(){
        Text type=text("type"),grade=text("\uC720\uC77C");
        assertEquals(-1,TooltipRarity.find(List.of(type,grade,text("attribute")),List.of(type,grade,text("description"))).index());
    }
    @Test void changesOnSameItemSecondLineAndLeavesInputImmutable(){
        Text type=text("type");
        var a=List.of(type,text("\uACE0\uAE09"));
        var b=List.of(type,text("\uC804\uC124"));
        assertEquals(TooltipRarity.Grade.HIGH,TooltipRarity.find(a,a).grade());
        assertEquals(TooltipRarity.Grade.LEGENDARY,TooltipRarity.find(b,b).grade());
        assertEquals("\uACE0\uAE09",a.get(1).getString());
    }
    @Test void longTypeCollisionUsesBodyFallbackWithoutMovingLeftText(){
        assertTrue(TooltipRarity.fits(128,70,24));
        assertTrue(TooltipRarity.fits(128,98,24));
        assertFalse(TooltipRarity.fits(128,99,24));
        assertFalse(TooltipRarity.fits(128,128,24));
    }
}
