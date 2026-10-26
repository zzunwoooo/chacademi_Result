package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static school.magiccodex.client.CodexShortcut.Result.*;

class CodexShortcutTest {
    @Test void opensOnRawPressWithoutVanillaPressQueue() {
        assertEquals(OPEN,new CodexShortcut().handle(73,1,true,true));
    }
    @Test void heldKeyDoesNotCloseOrReopenAfterScreenTransition() {
        var input=new CodexShortcut(); assertEquals(OPEN,input.handle(73,1,true,true));
        assertEquals(CONSUME,input.handle(73,2,true,false));
        assertEquals(CONSUME,input.handle(73,2,true,true));
        assertEquals(CONSUME,input.handle(73,1,true,true));
        assertEquals(PASS,input.handle(73,0,true,false));
        assertEquals(OPEN,input.handle(73,1,true,true));
    }
    @Test void freshPressInChatInventoryOrDisconnectedStateIsNotCaptured() {
        var input=new CodexShortcut(); assertEquals(PASS,input.handle(73,1,true,false));
        assertEquals(PASS,input.handle(73,2,true,false));
        assertEquals(PASS,input.handle(73,0,true,false));
    }
    @Test void respectsRemappedAndUnboundShortcut() {
        var input=new CodexShortcut(); assertEquals(PASS,input.handle(-1,1,false,true));
        assertEquals(PASS,input.handle(73,1,false,true));
        assertEquals(OPEN,input.handle(79,1,true,true));
    }
    @Test void repeatWithoutAnInitialPressCannotOpen() {
        var input=new CodexShortcut(); assertEquals(PASS,input.handle(73,2,true,true));
        assertEquals(PASS,input.handle(73,0,true,true));
    }
    @Test void unrelatedKeysRemainUsableWhileShortcutIsHeld() {
        var input=new CodexShortcut(); input.handle(73,1,true,true);
        assertEquals(PASS,input.handle(87,1,false,true));
        assertEquals(PASS,input.handle(87,0,false,true));
        assertEquals(CONSUME,input.handle(73,2,true,false));
    }
    @Test void focusLossOrDisconnectResetsHeldState() {
        var input=new CodexShortcut(); input.handle(73,1,true,true); input.reset();
        assertEquals(OPEN,input.handle(73,1,true,true));
    }
}
