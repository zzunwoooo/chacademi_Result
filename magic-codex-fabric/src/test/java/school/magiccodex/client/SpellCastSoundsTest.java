package school.magiccodex.client;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpellCastSoundsTest {
    private Path root(){return Path.of(".");}
    @Test void existing200AuthoredCuesKeepBoundedAudioAndAliases() {
        assertEquals(188,SpellCastSounds.values().size()); // 2026-10-26: 꺼진 마법 12개 삭제var events=new HashSet<String>();
        for(var cue:SpellCastSounds.values()) {
            assertNotNull(SpellCastSounds.byId(cue.id()));assertEquals(cue,SpellCastSounds.find(cue.label().replace(" ","")));
            assertEquals(cue,SpellCastSounds.find(cue.id().toUpperCase(Locale.ROOT)));assertTrue(events.add(cue.event()));
            assertTrue(cue.durationMillis()>=1000&&cue.durationMillis()<=3300);assertTrue(cue.volume()>0&&cue.volume()<=.65f);
        }
        assertNull(SpellCastSounds.find(null));assertNull(SpellCastSounds.find("not_a_spell"));
    }
    @Test void cuesResolveToUniqueOggsAndDoNotPreloadTheWholeLibrary()throws Exception{
        var resources=root().resolve("src/main/resources/assets/magiccodex");
        var manifest=JsonParser.parseString(Files.readString(resources.resolve("sounds.json"))).getAsJsonObject();
        var hashes=new HashSet<String>();long bytes=0;
        for(var cue:SpellCastSounds.values()){
            var entry=manifest.getAsJsonObject(cue.event()).getAsJsonArray("sounds");assertEquals(1,entry.size());
            var sound=entry.get(0).getAsJsonObject();assertFalse(sound.get("preload").getAsBoolean());assertFalse(sound.get("stream").getAsBoolean());
            assertEquals("magiccodex:cast/"+cue.id(),sound.get("name").getAsString());
            byte[] data=Files.readAllBytes(resources.resolve("sounds/cast/"+cue.id()+".ogg"));
            assertTrue(data.length>1000 && data.length<65000);bytes+=data.length;
            assertEquals("OggS",new String(data,0,4,java.nio.charset.StandardCharsets.US_ASCII));
            assertTrue(hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))),cue.id());
        }
        assertTrue(bytes<4_000_000,"Keep compressed cast audio small");
    }
}
