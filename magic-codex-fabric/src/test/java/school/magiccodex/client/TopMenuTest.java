package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import school.magiccodex.protocol.WalletProtocol;
import static org.junit.jupiter.api.Assertions.*;

class TopMenuTest {
    @Test void collapsedAndPartiallyRevealedIconsCannotBeClicked(){
        var state=new TopMenuState();
        assertEquals(-1,state.hit(178,18,198));
        assertEquals(-2,state.hit(223,18,198));
        state.toggle(1000);state.update(1120);
        assertEquals(.5f,state.progress(),.0001f);
        assertEquals(-2,state.hit(state.iconCenter(198,1),18,198));
        state.update(1240);
        for(int i=0;i<TopMenuState.LABELS.length;i++)assertEquals(i,state.hit(state.iconCenter(198,i),18,198));
        assertEquals(-2,state.hit(200,40,198));
    }
    @Test void reversingSlideDoesNotJumpAndClosesCompletely(){
        var state=new TopMenuState();state.toggle(1000);state.update(1090);
        float before=state.progress();state.toggle(1090);
        assertEquals(before,state.progress());
        state.update(1330);assertEquals(0,state.progress());assertFalse(state.expanded());
        assertEquals(198,state.width(198));
    }
    @Test void wideBalanceDoesNotShiftMenuHitTargetsAwayFromIcons(){
        var state=new TopMenuState();state.toggle(0);state.update(240);
        for(float moneyWidth:new float[]{0,45,87,138,500}){
            float base=TopMenuState.baseWidth(moneyWidth);
            // The season area must never trigger the shifted menu or expansion arrow.
            assertEquals(-2,state.hit(TopMenuState.SEASON_CENTER,18,base));
            assertTrue(TopMenuState.seasonHovered(TopMenuState.SEASON_CENTER,18));
            assertFalse(TopMenuState.seasonHovered(53,18));
            for(int i=0;i<TopMenuState.LABELS.length;i++)assertEquals(i,state.hit(state.iconCenter(base,i),18,base));
            assertEquals(-1,state.hit(state.arrowCenter(base),18,base));
            assertEquals(base+TopMenuState.LABELS.length*TopMenuState.STEP,state.width(base));
            assertTrue(state.arrowCenter(base)-state.iconCenter(base,TopMenuState.LABELS.length-1)>29);
        }
        assertEquals("마일리지상점",TopMenuState.command(0));
        assertEquals("우편함",TopMenuState.command(7));
        assertEquals("의뢰",TopMenuState.command(6));
        assertEquals("큐디치",TopMenuState.command(5));assertTrue(TopMenuState.enabled(3));assertEquals("지도",TopMenuState.command(3));
        assertArrayEquals(new String[]{"마일리지샵","내 정보","소셜","이동","도감","PVP","퀘스트","우편함","설정"},TopMenuState.LABELS);
    }
    @Test void groupsHaveFixedOrderAndRoomyVerticalTargets(){
        var state=new TopMenuState();state.toggle(0);state.update(240);
        for(int group:new int[]{1,2,4}){
            state.toggleGroup(group,300);state.update(480);
            float x=state.iconCenter(300,group);
            for(int row=0;row<TopMenuState.rows(group);row++){
                float top=TopMenuState.rowTop(row);
                assertEquals(TopMenuState.childHit(group,row),state.hit(x,top+1,300));
                assertEquals(TopMenuState.childHit(group,row),state.hit(x+30,top+60,300));
                assertEquals(-2,state.hit(x+40,top+30,300));
            }
        }
        assertEquals("스탯",TopMenuState.childLabel(1,0));
        assertEquals("장비",TopMenuState.childLabel(1,1));
        assertEquals("칭호",TopMenuState.childLabel(1,2));
        assertEquals("아티팩트 도감",TopMenuState.childLabel(4,2));
        assertEquals("물고기 도감",TopMenuState.childLabel(4,3));
        assertEquals(4,TopMenuState.rows(4));assertEquals(3,TopMenuState.rows(1));
        assertEquals("파티",TopMenuState.childLabel(2,1));
        assertEquals("기숙사",TopMenuState.childLabel(2,2));
        assertTrue(TopMenuState.ROW_HEIGHT>=64);
    }
    @Test void dropdownDoesNotClickDuringAnimationAndToggleClosesIt(){
        var state=new TopMenuState();state.toggle(0);state.update(240);
        state.toggleGroup(4,300);state.update(390);
        assertEquals(-2,state.hit(state.iconCenter(300,4),TopMenuState.rowTop(0)+20,300));
        state.update(480);assertEquals(140,state.hit(state.iconCenter(300,4),TopMenuState.rowTop(0)+20,300));
        state.toggleGroup(4,500);assertEquals(-1,state.group());
        state.toggleGroup(1,600);state.toggle(650);assertEquals(-1,state.group());
        state.toggleGroup(3,700);assertEquals(-1,state.group());
    }
    @Test void nonGroupCannotOpenDropdownAndBlankPaddingIsNotAnItem(){
        var state=new TopMenuState();state.toggle(0);state.update(240);
        for(int index:new int[]{-1,0,3,5,6,7,8,100}){state.toggleGroup(index,300);assertEquals(-1,state.group());}
        state.toggleGroup(1,300);state.update(480);float x=state.iconCenter(300,1);
        assertEquals(-2,state.hit(x,TopMenuState.DROPDOWN_TOP+1,300));
        assertEquals(-2,state.hit(x,TopMenuState.DROPDOWN_TOP+TopMenuState.dropdownHeight()-1,300));
        state.closeGroup();assertFalse(state.insideDropdown(x,80,300));
    }
    @Test void walletProtocolIsFixedSizeAndRejectsInvalidValues(){
        var snapshot=new WalletProtocol.Snapshot(true,12450.25);
        assertEquals(snapshot,WalletProtocol.decode(WalletProtocol.encode(snapshot)));
        assertTrue(WalletProtocol.validRequest(WalletProtocol.request()));
        assertFalse(WalletProtocol.validRequest(new byte[5]));
        assertFalse(WalletProtocol.validRequest(new byte[4]));
        assertThrows(IllegalArgumentException.class,()->WalletProtocol.decode(new byte[13]));
        byte[] packet=WalletProtocol.encode(snapshot);packet[4]=7;
        assertThrows(IllegalArgumentException.class,()->WalletProtocol.decode(packet));
        assertThrows(IllegalArgumentException.class,()->WalletProtocol.decode(Arrays.copyOf(packet,14)));
        assertThrows(IllegalArgumentException.class,()->new WalletProtocol.Snapshot(true,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new WalletProtocol.Snapshot(true,Double.POSITIVE_INFINITY));
        assertEquals(new WalletProtocol.Snapshot(true,-20),WalletProtocol.decode(WalletProtocol.encode(new WalletProtocol.Snapshot(true,-20))));
    }
}
