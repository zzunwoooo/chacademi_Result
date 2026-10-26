package kr.chacademy.story.quest;
/** Pure rules shared by packet acceptance and guide rendering; ready is deliberately not required. */
final class QuestGuidePolicy {
 static final double ARRIVAL = 10;
 static boolean accepts(boolean active,int current,int requested){return active && requested > 0 && current == requested;}
 static boolean tracks(boolean active,int current,int guide,int target,boolean sameWorld){return accepts(active,current,guide) && sameWorld && (target==QuestPackets.TARGET_LOCATION || target==QuestPackets.TARGET_NPC);}
 static boolean finite(double x,double y,double z){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z);}
 static boolean reached(double dx,double dz){return Double.isFinite(dx)&&Double.isFinite(dz)&&dx*dx+dz*dz<=ARRIVAL*ARRIVAL;}
}
