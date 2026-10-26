package school.magiccodex.client;

import java.util.*;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Presentation-only extraction from the complete, styled LORE sequence. */
final class TooltipRarity {
    enum Grade {
        GENERAL("\uC77C\uBC18",0xDCE3EA), HIGH("\uACE0\uAE09",0x71E5F2),
        UNIQUE("\uC720\uC77C",0xD2A8FF), LEGENDARY("\uC804\uC124",0xFFD582);
        final String label; final int color;
        Grade(String label,int color){this.label=label;this.color=color;}
    }
    record Lore(int index,Text type,Grade grade) {}
    static Grade recognize(Text second){
        if(second==null)return null;
        String plain=Formatting.strip(second.getString());
        if(plain==null)return null;
        plain=plain.strip();
        for(Grade grade:Grade.values())if(grade.label.equals(plain))return grade;
        return null;
    }
    static Lore find(List<Text> tooltipBody,List<Text> styledLore){
        if(styledLore.isEmpty())return new Lore(-1,null,null);
        int index=Collections.indexOfSubList(tooltipBody,styledLore);
        return index<0?new Lore(-1,null,null):new Lore(index,styledLore.getFirst(),
            styledLore.size()>1?recognize(styledLore.get(1)):null);
    }
    static boolean fits(int innerWidth,int typeWidth,int badgeWidth){
        return innerWidth-typeWidth>=badgeWidth+6;
    }
}
