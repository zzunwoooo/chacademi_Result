package kr.chacademi.chatlayout;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiStateTest {
    @TempDir Path dir;
    @Test void modesLocksAndSizesAreIndependent() {
        UiState s=new UiState();s.anchor(false,.25,.5);
        s.toggleLock(true);s.resize(true,.4,.3);s.maximize(true);
        assertNull(s.upperWidth);assertEquals(UiState.Mode.NORMAL,s.upper);
        s.resize(false,.5,.2);s.maximize(false);
        assertEquals(.5,s.lowerWidth);assertEquals(UiState.Mode.MAXIMIZED,s.lower);
        s.minimize(true);s.close(true);s.open(true);
        assertEquals(UiState.Mode.NORMAL,s.upper);assertEquals(UiState.Mode.MAXIMIZED,s.lower);
        s.toggleLock(true);s.resize(true,.4,.3);s.maximize(true);
        assertEquals(.4,s.upperWidth);assertEquals(UiState.Mode.MAXIMIZED,s.upper);
        assertEquals(.25,s.lowerX);assertEquals(.5,s.lowerY);
    }
    @Test void malformedAndMissingStateFallsBackSafely() throws Exception {
        var path=dir.resolve("layout.json");var store=new UiStateStore(path);
        assertEquals(.18,store.load().upperY);
        Files.writeString(path,"{ definitely invalid");
        assertEquals(UiState.Mode.NORMAL,store.load().upper);
        Files.writeString(path,"{\"upperX\":2,\"upperWidth\":-1,\"lowerX\":0.5}");
        var s=store.load();assertEquals(1,s.upperX);assertNull(s.upperWidth);assertNull(s.lowerX);
    }
    @Test void stateRoundTripPersistsOnlyUserLayoutAndModes() throws Exception {
        var path=dir.resolve("layout.json");var store=new UiStateStore(path);UiState s=new UiState();
        s.anchor(true,.2,.1);s.anchor(false,.4,.55);s.resize(false,.3,.12);
        s.toggleLock(false);s.close(true);store.save(s);
        var restored=store.load();
        assertEquals(.2,restored.upperX);assertEquals(.55,restored.lowerY);
        assertEquals(.12,restored.lowerHeight);assertTrue(restored.lowerLocked);
        assertEquals(UiState.Mode.CLOSED,restored.upper);assertFalse(Files.exists(dir.resolve("layout.json.tmp")));
        assertFalse(Files.readString(path).contains("messages"));
    }
}
