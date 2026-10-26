package school.magiccodex.client;
final class AppraisalTimeline {
 static final double STEP=1.15, SETTLE=.45, FADE=.55;
 static double arrivalEnd(int nodes){return Math.min(10,nodes+1)*STEP;}
 static double end(int nodes){return arrivalEnd(nodes)+SETTLE;}
 static boolean flying(double age,int nodes){return age>=0&&age<arrivalEnd(nodes);}
 static int shown(double age,int nodes){return Math.min(nodes,Math.max(0,(int)(age/STEP)));}
 static float brokenBlend(double age,int nodes){double t=Math.clamp((age-arrivalEnd(nodes))/FADE,0,1);return (float)(t*t*(3-2*t));}
}
