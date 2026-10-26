package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PermissionProtocol;
import static org.junit.jupiter.api.Assertions.*;

class PermissionViewTest {
    @Test void hudScopeCannotPretendToConfirmWholeCatalogAndOldScopeRepliesAreRejected() {
        var view=new PermissionView(); view.replace(CodexData.previewSpells());
        var narrow=view.open(List.of("magic.learned.magical_flame"));
        assertEquals(1,narrow.permissions().size());
        assertTrue(view.apply(new PermissionProtocol.Response(narrow.id(),List.of(true))));
        assertTrue(view.spells().getFirst().discovered());
        assertTrue(view.spells().stream().skip(1).noneMatch(CodexData.Spell::permissionKnown));
        var full=view.open(); assertEquals(18,full.permissions().size());
        assertFalse(view.apply(new PermissionProtocol.Response(narrow.id(),List.of(true))));
        assertTrue(view.spells().stream().noneMatch(CodexData.Spell::permissionKnown));
    }
    @Test void learnsAndRevokesUsingCurrentServerResults() throws Exception {
        var view = new PermissionView(); view.replace(CodexData.previewSpells());
        assertTrue(view.spells().stream().noneMatch(CodexData.Spell::permissionKnown));
        var open = view.open();
        var bits = new ArrayList<>(Collections.nCopies(18, false)); bits.set(0, true);
        // Use the exact shared Paper encoder -> Fabric decoder format.
        var response = PermissionProtocol.decodeResponse(PermissionProtocol.encodeResponse(new PermissionProtocol.Response(open.id(), bits)));
        assertTrue(view.apply(response)); assertTrue(view.spells().getFirst().discovered());
        assertTrue(view.spells().stream().allMatch(CodexData.Spell::permissionKnown));
        bits.set(0, false);
        assertTrue(view.apply(new PermissionProtocol.Response(open.id(), bits)));
        assertFalse(view.spells().getFirst().discovered());
    }
    @Test void oldRepliesCannotCrossCloseReloadOrConnections() {
        var view = new PermissionView(); view.replace(List.of(CodexData.previewSpells().getFirst()));
        var first = view.open(); var response = new PermissionProtocol.Response(first.id(), List.of(true));
        view.clear(); assertFalse(view.apply(response));
        var second = view.open(); assertNotEquals(first.id(), second.id()); assertFalse(view.apply(response));
        view.replace(List.of(CodexData.previewSpells().get(1)));
        assertFalse(view.apply(new PermissionProtocol.Response(second.id(), List.of(true))));
        assertFalse(view.spells().getFirst().permissionKnown());
    }
    @Test void mismatchedReplyCountIsIgnoredAndDuplicatePermissionsUseOneBit() {
        var view = new PermissionView(); var spell = CodexData.previewSpells().getFirst();
        view.replace(List.of(spell, spell)); var request = view.open();
        assertEquals(1, request.permissions().size());
        assertFalse(view.apply(new PermissionProtocol.Response(request.id(), List.of())));
        assertTrue(view.apply(new PermissionProtocol.Response(request.id(), List.of(true))));
        assertTrue(view.spells().stream().allMatch(CodexData.Spell::discovered));
    }
}
