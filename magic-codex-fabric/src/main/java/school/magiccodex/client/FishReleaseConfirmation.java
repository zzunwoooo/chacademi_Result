package school.magiccodex.client;
final class FishReleaseConfirmation {
 private long until;
 boolean click(long now){if(armed(now)){until=0;return true;}until=now+3000;return false;}
 boolean armed(long now){return now<until;}
 void reset(){until=0;}
}
