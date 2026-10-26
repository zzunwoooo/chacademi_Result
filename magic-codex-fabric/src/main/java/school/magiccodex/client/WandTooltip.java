package school.magiccodex.client;
import java.util.Locale;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
final class WandTooltip {
 private static NbtCompound data(ItemStack item){var d=item.get(DataComponentTypes.CUSTOM_DATA);return d==null?new NbtCompound():d.copyNbt().getCompound("PublicBukkitValues");}
 static boolean matches(ItemStack item){return data(item).contains("magiccodexbridge:wand_id");}
 private static Text label(String s,String face){return Text.literal(s).setStyle(Style.EMPTY.withFont(Identifier.of("magiccodex",face)));}
 static void remaining(DrawContext c,TextRenderer f,ItemStack item,int x,int y,int w){var d=data(item);int max=d.contains("magiccodexbridge:enhance_limit")?Math.clamp(d.getInt("magiccodexbridge:enhance_limit"),1,100):10;int used=Math.max(0,d.getInt("magiccodexbridge:enhance_attempts"));Text t=label("남은 강화 "+Math.max(0,max-used)+"/"+max,"tooltip_type");c.drawText(f,t,x+(w-f.getWidth(t))/2,y,0xFFEBD19F,false);}
 static void stats(DrawContext c,TextRenderer f,ItemStack item,int x,int y,int w){int middle=x+w/2;HudMesh.line(c,x+10,y-6,middle-5,y-6,.5f,0xA0969081);HudMesh.line(c,middle+5,y-6,x+w-10,y-6,.5f,0xA0969081);HudMesh.star(c,middle,y-6,2.5f,0xFFADA18C);var d=data(item);String[] keys={"wand_power","wand_mana","wand_haste"},names={"지능","마나 최대치","마법 가속"};int[] icons={0,2,7};for(int i=0;i<3;i++){int row=y+i*18;StatIcons.draw(c,icons[i],x+17,row+5,14);c.drawText(f,label(names[i],"tooltip_section"),x+29,row,0xFFDCE4EE,false);double v=d.getDouble("magiccodexbridge:"+keys[i]);if(!Double.isFinite(v)||v<0)v=0;Text value=label(String.format(Locale.ROOT,v==Math.rint(v)?"%.0f":"%.1f",v),"tooltip_section");c.drawText(f,value,x+w-12-f.getWidth(value),row,0xFF9EE5EB,false);}}
}

