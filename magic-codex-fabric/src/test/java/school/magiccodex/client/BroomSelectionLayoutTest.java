package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BroomSelectionLayoutTest {
 @Test void fiveCardsButtonsAndTooltipsStayInsidePanel(){
  for(int i=0;i<5;i++){var r=BroomSelectionLayout.card(i);assertTrue(r.x()>30&&r.x()+r.w()<1640&&r.y()>130&&r.y()+r.h()<790);if(i>0)assertTrue(BroomSelectionLayout.card(i-1).x()+r.w()<r.x());var t=BroomSelectionLayout.tooltip(i);assertTrue(t.x()>=30&&t.x()+t.w()<=1640&&t.y()+t.h()<892);}
 }
 @Test void zeroFiveSixAndTenOwnedBroomsHaveCorrectPages(){assertEquals(1,BroomSelectionLayout.pages(0));assertEquals(1,BroomSelectionLayout.pages(5));assertEquals(2,BroomSelectionLayout.pages(6));assertEquals(2,BroomSelectionLayout.pages(10));assertEquals(0,BroomSelectionLayout.page(1,0));assertEquals(1,BroomSelectionLayout.page(9,10));}
 @Test void tallerEquipmentFitsGuiScalesWithoutMovingHitCoordinates(){for(int[] s:new int[][]{{320,180},{426,240},{480,270},{640,360},{960,540},{1920,1080}}){var fit=BroomSelectionLayout.equipmentFit(s[0],s[1],true);assertTrue(fit.x()>=0&&fit.y()>=0);assertTrue(fit.x()+1672*fit.scale()<=s[0]);assertTrue(fit.y()+1005*fit.scale()<=s[1]);double y=590*BroomSelectionLayout.equipmentStretch(true);assertEquals(590,fit.localY(fit.y()+y*fit.scale())/BroomSelectionLayout.equipmentStretch(true),.001);}}
}
