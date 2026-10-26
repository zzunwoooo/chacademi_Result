package school.magiccodex.client;
import school.magiccodex.protocol.GeneralPresetModeProtocol;
final class NormalPresetMode {
 static final int OFF=GeneralPresetModeProtocol.OFF;
 static int next(int mode){return switch(mode){case 1->2;case 2->OFF;case OFF->1;default->throw new IllegalArgumentException("normal preset");};}
 static String label(int mode){return mode==OFF?"OFF":Integer.toString(mode);}
 static boolean inputAllowed(int mode,boolean combat){return combat||mode!=OFF;}
 private NormalPresetMode(){}
}
