package school.magiccodex.client;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.protocol.TamingProtocol;
import static org.junit.jupiter.api.Assertions.*;

class TamingChanceLabelTest {
    @TempDir Path directory;
    @Test void registrationUsesSavedCanonicalIdAndValidKeyNotModeOrCatalog() throws Exception {
        var state=new CastingState(); var settings=new SpellKeySettings();
        assertFalse(state.registered("taming"));
        settings.select(5,"taming"); state.setBindings(settings);
        assertFalse(state.registered("taming"));
        settings.bind(5,49); settings.save(directory.resolve("keys.yml"));
        state.setBindings(SpellKeySettings.load(directory.resolve("keys.yml")));
        assertTrue(state.registered("taming")); assertFalse(state.enabled());
        settings.clear(5); state.setBindings(settings);
        assertFalse(state.registered("taming"));
        settings.select(0,"taming_other");settings.bind(0,49);state.setBindings(settings);
        assertFalse(state.registered("taming"));
    }
    @Test void unfinishedDraftDoesNotAffectActiveBindings() {
        var settings=new SpellKeySettings();settings.select(0,"taming");settings.bind(0,49);
        var state=new CastingState();state.setBindings(settings);
        var draft=settings.copy();draft.clear(0);
        assertTrue(state.registered("taming"));
        state.setBindings(draft);assertFalse(state.registered("taming"));
    }
    @Test void terminalAndStaleStatesCannotStickForever() {
        for(int status=0;status<=6;status++) {
            var s=new TamingProtocol.State(1,status,2,"Iron golem",false,.08,0,0,0,"");
            assertFalse(TamingChanceLabel.visible(s,-1));
            assertFalse(TamingChanceLabel.visible(s,15001));
            assertEquals(status!=0&&status!=5,TamingChanceLabel.visible(s,0));
        }
        var target=new TamingProtocol.State(1,1,2,"",false,.08,0,0,0,"");
        assertTrue(TamingChanceLabel.visible(target,1200));assertFalse(TamingChanceLabel.visible(target,1201));
    }
    @Test void oneSmallLineAndBoundedReadableScale() {
        assertEquals("교화 8.0%",TamingChanceLabel.text(.08));
        assertEquals("교화 100.0%",TamingChanceLabel.text(1));
        assertEquals(.025f,TamingChanceLabel.scale(0));
        assertEquals(.03f,TamingChanceLabel.scale(5),.0001f);
        assertEquals(.065f,TamingChanceLabel.scale(100));
    }
}
