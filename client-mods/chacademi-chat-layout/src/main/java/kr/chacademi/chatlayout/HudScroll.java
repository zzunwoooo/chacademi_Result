package kr.chacademi.chatlayout;
/** Preserve fractional trackpad wheel motion without scrolling unrelated panes. */
public final class HudScroll {
 private Object owner;private double remainder;
 public int lines(Object next,double amount,boolean fine){
  if(!Double.isFinite(amount))return 0;
  if(owner!=next){owner=next;remainder=0;}
  remainder+=Math.max(-10,Math.min(10,amount))*(fine?1:3);
  int whole=(int)remainder;remainder-=whole;return whole;
 }
 public void reset(){owner=null;remainder=0;}
}