package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PartyHudProtocol;
import static org.junit.jupiter.api.Assertions.*;
import static school.magiccodex.client.PartyHudLayout.*;

class PartyHudTest {
    /** chacademi-party-velocity PartyHudTest.GOLDEN 과 같은 값 (Velocity 인코더 출력). */
    static final String GOLDEN="0102000000000000000100000000000000020006ecb0a8ecb9b400074178000041a0000042a4000042c80000000000000000000300000000000000040006eba3a8eb82980300";

    @Test void decodesVelocityPackets(){
        var members=PartyHudProtocol.decode(HexFormat.of().parseHex(GOLDEN));
        assertEquals(2,members.size());
        var a=members.get(0);assertEquals("차카",a.nickname());assertEquals(0,a.house());assertTrue(a.online()&&a.leader()&&a.known()&&a.manaKnown());
        assertEquals(15.5f,a.health());assertEquals(100f,a.maxMana());
        var b=members.get(1);assertEquals("루나",b.nickname());assertFalse(b.online()||b.leader()||b.known()||b.manaKnown());
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN),PartyHudProtocol.encode(members));
        assertTrue(PartyHudProtocol.decode(new byte[]{1,0}).isEmpty());
    }
    @Test void rejectsMalformedPackets(){
        byte[] good=HexFormat.of().parseHex(GOLDEN);
        assertThrows(IllegalArgumentException.class,()->PartyHudProtocol.decode(Arrays.copyOf(good,good.length-1)));
        assertThrows(IllegalArgumentException.class,()->PartyHudProtocol.decode(Arrays.copyOf(good,good.length+1)));
        byte[] version=good.clone();version[0]=2;assertThrows(IllegalArgumentException.class,()->PartyHudProtocol.decode(version));
        assertThrows(IllegalArgumentException.class,()->PartyHudProtocol.decode(new byte[]{1,17}));
    }
    @Test void hitAreasAndResize(){
        int n=3;float h=height(n);
        assertEquals(GRIP_HIT,hit(WIDTH-2,h-2,n));
        assertEquals(HEALTH,hit(BAR_X+10,rowTop(1)+HEALTH_Y+4,n));
        assertEquals(MANA,hit(BAR_X+10,rowTop(2)+MANA_Y+4,n));
        assertEquals(BODY,hit(10,10,n));assertEquals(NONE,hit(-1,10,n));assertEquals(NONE,hit(10,h+1,n));
        assertEquals(2f,resized(1,0,0,300,200,600,400),1e-4);
        assertEquals(MAX_SCALE,resized(2,0,0,10,10,1000,1000));assertEquals(MIN_SCALE,resized(1,0,0,300,200,1,1));
    }
    @Test void staysOnScreenAndFormatsNumbers(){
        float s=scale(1920,1080,1);
        assertEquals(1920-WIDTH*s,originX(.99f,1920,s),1e-3);assertEquals(1920-WIDTH*s,originX(Float.NaN,1920,s),1e-3);
        assertEquals(1080-height(4)*s,originY(1,1080,s,4),1e-3);
        assertEquals("15.5",number(15.5f));assertEquals("20",number(20f));
        assertEquals(0,fraction(5,0));assertEquals(1,fraction(25,20));assertEquals(.5f,fraction(10,20));
    }
    @Test void everyHouseHasAPackagedIcon(){
        for(int house=0;house<4;house++){
            String icon=icon(house);assertNotNull(icon);
            assertNotNull(getClass().getResourceAsStream("/assets/magiccodex/textures/gui/party/"+icon+".png"),icon);
        }
        assertNull(icon(-1));
        // PartyHudClient 의 막대 크기 상수와 실제 PNG 가 맞아야 9조각 자르기가 어긋나지 않는다.
        var sizes=Map.of("bar_track",725,"health_bar",886,"mana_bar",768);
        for(var e:sizes.entrySet())try(var in=getClass().getResourceAsStream("/assets/magiccodex/textures/gui/party/"+e.getKey()+".png")){
            assertNotNull(in,e.getKey());var image=javax.imageio.ImageIO.read(in);
            assertEquals((int)e.getValue(),image.getWidth(),e.getKey());assertEquals(36,image.getHeight(),e.getKey());
            assertTrue((image.getRGB(0,0)>>>24)<=8,"transparent corner "+e.getKey());
        }catch(java.io.IOException io){fail(io);}
    }
    @Test void fullPartyFitsAtMaximumZoom(){
        for(int count:new int[]{8,16})for(int[] size:new int[][]{{1920,1080},{640,360},{480,270}}){
            float s=fittedScale(size[0],size[1],MAX_SCALE,count);
            assertTrue(WIDTH*s<=size[0]+.01f);assertTrue(height(count)*s<=size[1]+.01f);
        }
    }
    @Test void defaultRightCenterFitsWithoutChangingManualFractions(){
        for(int n:new int[]{1,4,16})for(int[] size:new int[][]{{1920,1080},{640,360},{320,180}}){
            float s=fittedScale(size[0],size[1],DEFAULT_SCALE,n);
            float x=defaultOriginX(size[0],s),y=defaultOriginY(size[1],s,n);
            assertTrue(x>=0&&x+WIDTH*s<=size[0]+.01f);
            assertEquals(size[1]/2f,y+height(n)*s/2,.01f);
            assertEquals(Math.min(size[0]*.2f,size[0]-WIDTH*s),originX(.2f,size[0],s),.01f);
        }
    }
}
