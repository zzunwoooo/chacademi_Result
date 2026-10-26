package kr.chacademy.motion.broom;
import java.util.Random;
/** Logical movement directions, so rebound controls work without raw key codes. */
public final class BroomCatchSequence {
 private final int[] sequence=new int[4];private int progress,previous;
 public BroomCatchSequence(long seed,int held){var r=new Random(seed);previous=held;for(int i=0;i<sequence.length;i++){int n;do{n=r.nextInt(4);}while(i>0&&n==sequence[i-1]);sequence[i]=n;}}
 public int[] keys(){return sequence.clone();}public int progress(){return progress;}public boolean complete(){return progress==sequence.length;}
 public boolean input(int mask){int edge=mask&~previous;previous=mask;if(edge==0||complete())return complete();if(Integer.bitCount(edge)!=1||edge!=(1<<sequence[progress])){progress=0;return false;}progress++;return complete();}
}
