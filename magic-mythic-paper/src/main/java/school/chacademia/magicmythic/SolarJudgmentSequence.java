package school.chacademia.magicmythic;
import java.util.*;
/** Visual precharge starts before chant completion so the approved 240-tick gameplay cast is unchanged. Geometry comes from delivered NOTES. */
final class SolarJudgmentSequence {
 static final int FIRE_DELAY=0,BEAM_TICKS=60,FINISH_DELAY=70;
 static int formed(int age){return Math.clamp(age<8?0:1+(age-8)/40,0,6);}
 static List<String> cancel(int age){var out=new ArrayList<String>();out.add("external:cancel");for(int i=0;i<formed(age);i++)out.add("external:struct_cancel_"+i);return out;}
 static List<String> events(int age,int chantTicks){
  int t=age-chantTicks;
  if(t==-12)return List.of("external:star_link");
  if(t==FIRE_DELAY)return List.of("external:laser_fire");
  if(t==FIRE_DELAY+4)return List.of("external:ground_hit");
  if(t==FIRE_DELAY+50)return List.of("external:laser_final","external:ground_final");
  if(t==FIRE_DELAY+BEAM_TICKS)return List.of("external:laser_end");
  if(t==FINISH_DELAY){var out=new ArrayList<String>();for(int i=0;i<6;i++)out.add("external:struct_break_"+i);out.add("external:link_break");return out;}
  return List.of();
 }
}
