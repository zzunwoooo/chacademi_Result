package kr.chacademi.chatlayout;
/** Right-click suppression applies only to an interactive chat surface. */
public final class ChatPointerPolicy {
 private ChatPointerPolicy(){}
 public static boolean block(int button,boolean chatScreen,boolean insideChat){
  return button==1&&chatScreen&&insideChat;
 }
}
