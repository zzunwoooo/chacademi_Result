package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayerHudTest {
    @Test void oneMovingStarPerRankAndConnectionThreshold(){
        var g=new HudConstellation();
        for(int n=1;n<=9;n++){
            g.update(n,5);assertEquals(n,g.count());assertEquals(n>=5,g.connected());
            for(int i=0;i<n;i++)assertTrue(Math.hypot(g.x(i),g.y(i))<1.07);
            if(n>=5)for(int i=0;i<n;i++)assertEquals(1.065,Math.hypot(g.x(i),g.y(i)),.0001);
        }
        assertThrows(IllegalArgumentException.class,()->g.update(0,0));
        assertThrows(IllegalArgumentException.class,()->g.update(10,0));
    }
    @Test void animationMovesAndHasOuterOrbit(){
        var g=new HudConstellation();g.update(3,0);float x=g.x(0);
        g.update(3,3);assertNotEquals(x,g.x(0));
        assertTrue(Math.hypot(g.x(0),g.y(0))>1);assertTrue(Math.hypot(g.x(1),g.y(1))<1);
    }
    @Test void changingGuiScalePreservesPhysicalHudSize(){
        var a=PlayerHudLayout.of(960,540);var b=PlayerHudLayout.of(640,360);var c=PlayerHudLayout.of(480,270);
        assertEquals(a.scale()*2,b.scale()*3,.001);
        assertEquals(a.scale()*2,c.scale()*4,.001);
        // Skill HUD retains its own size; compare both groups in screen coordinates.
        assertTrue((a.slotX(8)+PlayerHudLayout.CELL)*a.scale()<960-402*Math.min(960/1672f,540/941f));
        assertTrue(a.hotbarLeft()>365);
    }
    @Test void fractionsHandleEmptyAndUntrustedLimits(){
        assertEquals(0,PlayerHudLayout.fraction(20,0));assertEquals(0,PlayerHudLayout.fraction(Float.NaN,20));
        assertEquals(1,PlayerHudLayout.fraction(25,20));assertEquals(0,PlayerHudLayout.fraction(-2,20));
        assertEquals(.65f,PlayerHudLayout.fraction(65,100),.0001);
    }
    @Test void enlargedPortraitAndInventoryRemainSeparatedAcrossAspectRatios(){
        for(int[] size:new int[][]{{640,360},{480,360},{360,640},{1280,360}}){
            var l=PlayerHudLayout.of(size[0],size[1]);
            float orbit=PlayerHudLayout.STAR_RADIUS*1.065f+5;
            assertTrue(l.frameX()-orbit>0);
            assertTrue(l.frameY()+orbit<l.height());
            assertTrue(l.frameX()+orbit<l.hotbarLeft());
            assertTrue(l.vitalX()+PlayerHudLayout.VITAL_WIDTH<l.hotbarLeft());
            assertTrue(l.manaY()+13<l.height());
            assertTrue(l.healthY()+16<l.manaY()-28);
        }
    }
}
