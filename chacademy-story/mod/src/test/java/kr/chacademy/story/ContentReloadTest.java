package kr.chacademy.story;
import kr.chacademy.cutscene.data.ContentReload;
import kr.chacademy.story.dialogue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentReloadTest {
    @TempDir Path dir;
    private void write(String id,String commands)throws Exception {
        Files.createDirectories(dir.resolve(id));
        Files.writeString(dir.resolve(id).resolve("dialogue.yml"),"scenes:\n  wake:\n    lines:\n      - text: hello\ncommands:\n"+commands);
    }
    private ContentReload.Result reload(){return ContentReload.validate(dir,"dialogue.yml",id->DialogueLoader.load(dir,id));}
    @Test void rereadsCommandsPreservesActiveSnapshotAndNeverWritesProgressOrRunsEffects()throws Exception {
        write("chapter","  end: [help]\n");
        var active=DialogueLoader.load(dir,"chapter");
        Path progress=dir.resolve("_progress/player.properties");Files.createDirectories(progress.getParent());
        var ledger=new LocalDialogueStore(progress);assertTrue(ledger.complete("chapter"));byte[] before=Files.readAllBytes(progress);
        write("chapter","  end: [chaca_reward ch1-3.end]\n");
        assertEquals(List.of("chapter"),reload().validIds());
        assertEquals(List.of("help"),active.commands().end());
        assertEquals(List.of("chaca_reward ch1-3.end"),DialogueLoader.load(dir,"chapter").commands().end());
        assertArrayEquals(before,Files.readAllBytes(progress));assertTrue(new LocalDialogueStore(progress).done("chapter"));
        try(var files=Files.walk(dir)){assertEquals(2,files.filter(Files::isRegularFile).count());}
    }
    @Test void reportsEachBrokenFileAndDetectsRepairWithoutCachedDialogue()throws Exception {
        write("good","  end: [help]\n");write("bad","  end: not-a-list\n");write("BAD ID","  end: []\n");
        var result=reload();assertEquals(List.of("good"),result.validIds());assertEquals(Set.of("bad","BAD ID"),result.errors().keySet());
        write("bad","  end: []\n");assertEquals(List.of("bad","good"),reload().validIds());
    }
    @Test void missingRootDoesNotCreateAnythingAndNonDirectoryReportsError()throws Exception {
        Path missing=dir.resolve("missing");assertTrue(ContentReload.validate(missing,"dialogue.yml",id->{throw new AssertionError();}).errors().isEmpty());assertFalse(Files.exists(missing));
        Files.writeString(missing,"file");assertFalse(ContentReload.validate(missing,"dialogue.yml",id->{throw new AssertionError();}).errors().isEmpty());
    }
}
