package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortraitPreviewTest {
 @Test void fullPortraitFitsWithoutTopCropping(){
  for(int[] screen:new int[][]{{320,180},{800,600},{1920,1080},{1080,1920}})
   for(int[] image:new int[][]{{1024,1536},{2048,2048},{1536,1024}}){
    var b=PortraitPreviewLayout.fit(screen[0],screen[1],image[0],image[1]);
    assertTrue(b.x()>=0&&b.y()>=0);assertTrue(b.x()+b.width()<=screen[0]);assertTrue(b.y()+b.height()<=screen[1]);
    assertEquals(image[0]/(double)image[1],b.width()/(double)b.height(),0.025);
   }
 }
 @Test void oldSessionAndOlderImageCannotInstallAfterNewLoad(){
  var fence=new PortraitLoadFence();long first=fence.next();assertTrue(fence.current(first));
  long next=fence.next();assertFalse(fence.current(first));assertTrue(fence.current(next));
  fence.next();assertFalse(fence.current(next));
 }
 @Test void commandDefersScreenUntilFollowingTickAndDropsDisconnectedRequest(){
  var queue=new NextTickAction();Object connection=new Object(),world=new Object();int[] opened={0};
  queue.schedule(connection,world,()->opened[0]++);queue.drain(connection,world);assertEquals(0,opened[0]);
  queue.advance();queue.drain(connection,world);assertEquals(1,opened[0]);
  queue.schedule(connection,world,()->opened[0]++);queue.advance();queue.drain(new Object(),world);assertEquals(1,opened[0]);
 }
}
