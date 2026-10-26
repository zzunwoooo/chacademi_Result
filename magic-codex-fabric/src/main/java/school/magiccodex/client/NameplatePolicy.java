package school.magiccodex.client;
/** Deliberately depth-only: unlike vanilla's faint through-wall pass, flags never reveal through solid walls. */
public final class NameplatePolicy {
 public static final float WORLD_WIDTH=1.1f,NAME_SIZE=210;
 public static int textureWidth(int style){return style==4?2046:2048;}
 public static float nameCenterY(boolean titled){return titled?85:29;}
 public static boolean selfPreview(boolean firstPerson,boolean cursorScreen,boolean hiddenHud,boolean alive,boolean invisible,boolean spectator){return !firstPerson&&cursorScreen&&!hiddenHud&&alive&&!invisible&&!spectator;}
 private NameplatePolicy(){}
 public static boolean visible(boolean self,boolean camera,boolean hiddenHud,boolean alive,boolean invisible,boolean spectator,boolean passengers,boolean sneaky,double distance,String rule,boolean viewerHasTeam,boolean sameTeam){
  if(self||camera||hiddenHud||!alive||invisible||spectator||passengers||!Double.isFinite(distance)||distance>48*48||(sneaky&&distance>=32*32))return false;
  return switch(rule){case "NEVER"->false;case "HIDE_FOR_OTHER_TEAMS"->!viewerHasTeam||sameTeam;case "HIDE_FOR_OWN_TEAM"->!viewerHasTeam||!sameTeam;default->true;};
 }
}
