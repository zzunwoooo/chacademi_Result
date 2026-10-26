package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerTurnPortraitLayoutTest {
    @Test void waistFramingReducesOriginalScaleAndPreservesHead(){
        var b=new PlayerTurnPortraitLayout.Bounds(0,0,1024,1536);
        var p=PlayerTurnPortraitLayout.place(1024,1536,b);
        assertEquals(440,p.width());assertEquals(75,p.y());assertEquals(195,p.x());
        assertEquals(440.0/700,p.width()/700.0,1e-6);
        // Existing portrait belt begins around source y=1320, at the panel edge.
        assertEquals(642.19,p.y()+1320*p.width()/1024.0,1);
        assertTrue(p.y()+1536*p.width()/1024.0>PlayerTurnPortraitLayout.PANEL_TOP);
    }
    @Test void transparentPaddingDoesNotChangeCharacterFraming(){
        var tight=PlayerTurnPortraitLayout.place(512,768,new PlayerTurnPortraitLayout.Bounds(0,0,512,768));
        var b=PlayerTurnPortraitLayout.bounds(1024,1536,(x,y)->x>=200&&x<712&&y>=300&&y<1068?255:0);
        assertEquals(new PlayerTurnPortraitLayout.Bounds(200,300,512,768),b);
        var padded=PlayerTurnPortraitLayout.place(1024,1536,b);
        assertEquals(tight.x(),padded.x()+200*padded.width()/1024.0,1);
        assertEquals(tight.y(),padded.y()+300*padded.width()/1024.0,1);
        assertEquals(tight.width()/512.0,padded.width()/1024.0,1e-6);
    }
    @Test void transparentAndWideImagesHaveSafeFallbackAndKeepAspectRatio(){
        assertEquals(new PlayerTurnPortraitLayout.Bounds(0,0,30,40),PlayerTurnPortraitLayout.bounds(30,40,(x,y)->0));
        for(int[] size:new int[][]{{1024,1536},{1536,1024},{2048,2048},{256,2048}}){
            var p=PlayerTurnPortraitLayout.place(size[0],size[1],new PlayerTurnPortraitLayout.Bounds(0,0,size[0],size[1]));
            assertTrue(p.x()>=65);assertTrue(p.x()+p.width()<=765);assertTrue(p.y()>=75);
            assertTrue(p.y()+p.width()*(double)size[1]/size[0]>=PlayerTurnPortraitLayout.CLIP_BOTTOM-1);
            assertEquals(size[0]/(double)size[1],p.width()/(double)Math.round(p.width()*(float)size[1]/size[0]),.01);
        }
    }
    @Test void windowFullscreenAndGuiScalesKeepHeadAbovePanel(){
        for(int[] frame:new int[][]{{1253,699},{1280,720},{1920,1080},{2560,1440},{800,600}})
            for(int gui:new int[]{1,2,3,4}){
                double w=Math.ceil(frame[0]/(double)gui),h=Math.ceil(frame[1]/(double)gui);
                double scale=Math.min(w/1600,h/900),offset=(h-900*scale)/2;
                double head=offset+75*scale,panel=offset+639*scale;
                assertTrue(head>=0&&head<panel&&panel<h);
                assertEquals(564*scale,panel-head,1e-6);
            }
    }
    @Test void externalPortraitsFitTheNpcPortraitBoxKeepingAspectRatio(){
        // 서버 NPC 초상화와 같은 크기면 정확히 같은 자리 (65,-5 에 700x1050)
        assertEquals(new PlayerTurnPortraitLayout.Placement(65,-5,700),PlayerTurnPortraitLayout.fit(1024,1536));
        for(int[] size:new int[][]{{2048,2048},{512,2048},{2048,512},{700,1050},{1,1},{333,777}}){
            var p=PlayerTurnPortraitLayout.fit(size[0],size[1]);
            int height=Math.round(p.width()*(float)size[1]/size[0]);
            assertTrue(p.x()>=65&&p.x()+p.width()<=765,"가로가 칸 안");
            assertTrue(p.y()>=-5&&p.y()+height<=1045+1,"세로가 칸 안");
            assertEquals(415,p.x()+p.width()/2.0,1,"가운데 정렬");
        }
        assertEquals(new PlayerTurnPortraitLayout.Placement(65,-5,700),PlayerTurnPortraitLayout.fit(0,0));
    }
    @Test void sharedClipReachesBehindVisiblePanelWithoutMovingPortraitAtEveryViewport(){
        // Synthetic full-canvas and padded silhouettes; no private portrait bytes.
        var full=PlayerTurnPortraitLayout.place(1024,1536,new PlayerTurnPortraitLayout.Bounds(0,0,1024,1536));
        assertEquals(new PlayerTurnPortraitLayout.Placement(195,75,440),full);
        double panelGoldTop=PlayerTurnPortraitLayout.PANEL_TOP+(214-199)*220.0/316;
        assertTrue(PlayerTurnPortraitLayout.CLIP_BOTTOM>panelGoldTop);
        for(int[] viewport:new int[][]{{1272,704},{1253,699},{1280,720},{1920,1080},{2560,1440},{800,600}})
            for(int gui:new int[]{1,2,3,4}){
                double w=Math.ceil(viewport[0]/(double)gui),h=Math.ceil(viewport[1]/(double)gui);
                double scale=Math.min(w/1600,h/900),offset=(h-900*scale)/2;
                double oldClip=offset+PlayerTurnPortraitLayout.PANEL_TOP*scale;
                double gold=offset+panelGoldTop*scale;
                double sharedClip=offset+PlayerTurnPortraitLayout.CLIP_BOTTOM*scale;
                assertTrue(oldClip<gold,"old clip leaves a gap above the visible panel");
                assertTrue(sharedClip>gold,"shared clip must overlap behind the visible panel");
                assertTrue(offset+full.y()*scale<gold,"head position remains unchanged");
                assertTrue(full.y()+660>PlayerTurnPortraitLayout.CLIP_BOTTOM);
            }
    }
}
