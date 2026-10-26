package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatPointerPolicyTest {
 @Test void consumesRightClickInsideInteractiveChat(){assertTrue(ChatPointerPolicy.block(1,true,true));}
 @Test void preservesLeftClickAndDragPress(){assertFalse(ChatPointerPolicy.block(0,true,true));}
 @Test void preservesInventoryAndShopRightClick(){assertFalse(ChatPointerPolicy.block(1,false,true));}
 @Test void preservesRightClickOutsideChat(){assertFalse(ChatPointerPolicy.block(1,true,false));}
 @Test void preservesOtherButtons(){assertFalse(ChatPointerPolicy.block(2,true,true));}
}
