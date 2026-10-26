package school.magiccodex.paper;

import java.io.IOException;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PermissionProtocol;
import static org.junit.jupiter.api.Assertions.*;

class PermissionProtocolTest {
    @Test void allActionsRoundTripAndControlsAreThirteenBytes() throws Exception {
        for (int action = 1; action <= 3; action++) {
            var request = new PermissionProtocol.Request(action, 42, action == 1 ? List.of("magic.learned.flame") : List.of());
            var bytes = PermissionProtocol.encodeRequest(request);
            assertEquals(request, PermissionProtocol.decodeRequest(bytes));
            if (action != 1) assertEquals(13, bytes.length);
        }
    }
    @Test void packedBitsRoundTripAtEveryBoundary() throws Exception {
        for (int count : new int[]{0, 1, 7, 8, 9, 18, 255, 256, 300, 511, 512}) {
            var response = new PermissionProtocol.Response(7, IntStream.range(0, count).mapToObj(i -> i % 3 == 0).toList());
            byte[] bytes = PermissionProtocol.encodeResponse(response);
            assertEquals(14 + (count + 7) / 8, bytes.length);
            assertEquals(response, PermissionProtocol.decodeResponse(bytes));
        }
    }
    @Test void rejectsDuplicatesInvalidNamesAndExcessiveCounts() {
        for (List<String> names : List.of(List.of("magic.a", "magic.a"), List.of("/op user"), List.of("x".repeat(101)),
                IntStream.range(0, PermissionProtocol.MAX_PERMISSIONS + 1).mapToObj(i -> "magic." + i).toList())) {
            assertThrows(IllegalArgumentException.class, () -> PermissionProtocol.encodeRequest(new PermissionProtocol.Request(1, 1, names)));
        }
    }
    @Test void threeHundredSpellPermissionsRoundTripWithinExistingPacketBudget() throws Exception {
        var names=IntStream.range(0,300).mapToObj(i->"magic.learned.catalog_"+i).toList();
        var request=new PermissionProtocol.Request(PermissionProtocol.OPEN,42,names);
        var bytes=PermissionProtocol.encodeRequest(request);
        assertTrue(bytes.length<27000);
        assertEquals(request,PermissionProtocol.decodeRequest(bytes));
    }
    @Test void truncatedVersionMismatchAndTrailingBytesAreRejected() throws Exception {
        byte[] valid = PermissionProtocol.encodeRequest(new PermissionProtocol.Request(1, 1, List.of("magic.a")));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> PermissionProtocol.decodeRequest(truncated));
        }
        assertThrows(IOException.class, () -> PermissionProtocol.decodeRequest(Arrays.copyOf(valid, valid.length + 1)));
        valid[3] = 99; assertThrows(IOException.class, () -> PermissionProtocol.decodeRequest(valid));
        assertThrows(IOException.class, () -> PermissionProtocol.decodeRequest(new byte[27001]));
    }
    @Test void paddingCannotHideExtraPermissionBits() {
        byte[] bytes = PermissionProtocol.encodeResponse(new PermissionProtocol.Response(1, List.of(true)));
        bytes[bytes.length - 1] = 3;
        assertThrows(IOException.class, () -> PermissionProtocol.decodeResponse(bytes));
    }
    @Test void fitCapsBytesAndCountSoOpenNeverThrows() throws Exception {
        // 512 maximum-length names would be ~52 KB; the fitted prefix must still encode.
        var huge = IntStream.range(0, 600).mapToObj(i -> String.format("magic.learned.%086d", i)).toList();
        var fitted = PermissionProtocol.fit(huge);
        assertTrue(fitted.size() < 512 && !fitted.isEmpty());
        assertEquals(huge.subList(0, fitted.size()), fitted);
        byte[] bytes = PermissionProtocol.encodeRequest(new PermissionProtocol.Request(PermissionProtocol.OPEN, 1, fitted));
        assertTrue(bytes.length <= PermissionProtocol.MAX_BYTES);
        var plus = new ArrayList<>(fitted); plus.add(huge.get(fitted.size()));
        assertThrows(IllegalArgumentException.class, () -> PermissionProtocol.encodeRequest(new PermissionProtocol.Request(PermissionProtocol.OPEN, 1, plus)));
        var shortNames = IntStream.range(0, 700).mapToObj(i -> "p" + i).toList();
        assertEquals(PermissionProtocol.MAX_PERMISSIONS, PermissionProtocol.fit(shortNames).size());
        assertEquals(List.of("a.b", "c"), PermissionProtocol.fit(Arrays.asList("a.b", "Bad", "a.b", null, "c")));
    }
}
