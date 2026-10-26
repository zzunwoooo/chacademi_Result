package school.magiccodex.client;
/** Pure edge reservation: a dismount press/repeat/release must never also cast a spell. */
final class BroomKeyReservation {
 private boolean held;
 boolean input(boolean sneak,boolean riding,int action){
  if(!sneak||(!riding&&!held))return false;
  held=action!=0;
  return true;
 }
 boolean held(){return held;}
 void clear(){held=false;}
}
