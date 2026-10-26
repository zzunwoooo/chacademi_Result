package school.magiccodex.client;
/** One outstanding request, monotonic session sequence, idempotent completed responses. */
final class NpcTalkFlow {
 private int next,pending,floor,lastLine;
 private long sentAt;
 int begin(long now){if(pending!=0)return 0;pending=++next;floor=next;sentAt=now;return pending;}
 boolean waiting(){return pending!=0;}
 int pending(){return pending;}
 boolean matches(int sequence){return pending!=0&&sequence==pending;}
 void rejected(int sequence){if(matches(sequence))pending=0;}
 boolean line(int sequence){
  if(sequence<=0||sequence<floor||sequence>next||sequence==lastLine)return false;
  lastLine=sequence;if(matches(sequence))pending=0;return true;
 }
 boolean current(int sequence){return sequence>=floor&&sequence<=next;}
 boolean timeout(long now){if(pending==0||now-sentAt<=20000)return false;pending=0;return true;}
}