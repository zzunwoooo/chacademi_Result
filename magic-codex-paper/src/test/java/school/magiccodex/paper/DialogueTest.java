package school.magiccodex.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class DialogueTest {
    @TempDir Path dir;
    private DialogueDefinition sample()throws Exception{return DialogueDefinition.decode("elena",Files.readString(Path.of("src/main/resources/dialogues/elena.yml")));}
    @Test void graphsRoundTripAndConditions()throws Exception{
        var d=sample();assertEquals(d,DialogueDefinition.decode(d.id(),d.encode()));assertEquals(DialogueDefinition.revision(d),DialogueDefinition.revision(DialogueDefinition.decode(d.id(),d.encode())));
        var fields=new HashMap<>(d.fields());fields.put("node.start.choice.0.next","missing");assertThrows(IllegalArgumentException.class,()->DialogueDefinition.fromFields("elena",fields));
        assertTrue(DialogueDefinition.matches(List.of("story clue active","not-flag met yes","permission dialogue.use"),Map.of("story.clue","active"),p->p.equals("dialogue.use")));
        assertFalse(DialogueDefinition.matches(List.of("flag met yes"),Map.of(),p->true));
        assertFalse(DialogueDefinition.matches(List.of("story clue active"),Map.of("story.clue","completed"),p->true));
        assertTrue(DialogueDefinition.matches(List.of("quest patrol completed","custom circle 3"),Map.of(),p->false,(key,arg)->key.equals("quest:patrol")&&arg.equals("completed")||key.equals("custom:circle")&&arg.equals("3")));
        assertFalse(DialogueDefinition.matches(List.of("custom unavailable arg"),Map.of(),p->true));
        var arden=DialogueDefinition.decode("arden",Files.readString(Path.of("src/main/resources/dialogues/arden.yml")));assertTrue(arden.nodes().containsKey("completed"));
    }
    @Test void protocolRejectsTruncationSpoofsAndTrailingBytes(){
        var request=new DialogueProtocol.Request(UUID.randomUUID().toString(),3,"c1",false);byte[] packet=DialogueProtocol.encode(request);assertEquals(request,DialogueProtocol.request(packet));
        assertThrows(IllegalArgumentException.class,()->DialogueProtocol.request(Arrays.copyOf(packet,packet.length-1)));
        assertThrows(IllegalArgumentException.class,()->DialogueProtocol.request(Arrays.copyOf(packet,packet.length+1)));
        assertThrows(IllegalArgumentException.class,()->DialogueProtocol.request(DialogueProtocol.encode(new DialogueProtocol.Request("fake",0,"command op",false))));
    }
    @Test void crossConnectionFencingAndOnceOnlyEffects()throws Exception{
        var settings=new DatabaseSettings(false,"","","");UUID player=UUID.randomUUID();Path db=dir.resolve("dialogue.db");
        try(var a=new DialogueStore(settings,db);var b=new DialogueStore(settings,db)){
            var before=a.state(player);var stale=b.state(player);
            var first=a.transition(player,before,"elena:start:c0",List.of("story clue active","command give {player} diamond 1"));
            assertEquals("active",b.state(player).values().get("story.clue"));assertEquals(1,a.audit(player).size());
            assertThrows(IllegalStateException.class,()->b.transition(player,stale,"elena:start:c0",List.of("command give {player} diamond 1")));
            assertThrows(IllegalStateException.class,()->b.transition(player,b.state(player),"elena:start:c0",List.of("command give {player} diamond 1")));
            a.finish(player,first.receipt());
            var repeat=b.transition(player,b.state(player),"elena:start:c0",List.of("command give {player} diamond 1"));assertTrue(repeat.external().isEmpty());assertTrue(a.audit(player).isEmpty());
            assertThrows(java.sql.SQLException.class,()->a.finish(player,first.receipt()));
        }
        try(var reopened=new DialogueStore(settings,db)){assertEquals("active",reopened.state(player).values().get("story.clue"));}
    }
    @Test void administratorsCannotOverwriteEachOther()throws Exception{
        try(var store=new DialogueStore(new DatabaseSettings(false,"","",""),dir.resolve("catalog.db"))){var d=sample();assertTrue(store.edit(d.id(),"",d));assertFalse(store.edit(d.id(),"",d));var f=new HashMap<>(d.fields());f.put("title","새 제목");var edit=DialogueDefinition.fromFields(d.id(),f);assertTrue(store.edit(d.id(),DialogueDefinition.revision(d),edit));assertFalse(store.edit(d.id(),DialogueDefinition.revision(d),d));assertFalse(store.edit(d.id(),DialogueDefinition.revision(d),null));assertEquals("새 제목",store.catalog().get(d.id()).title());}
    }
    @Test void sampleDocumentsFitNetworkEnvelope()throws Exception{var d=sample();var r=new DialogueAdminProtocol.Response("",List.of(),d.id(),DialogueDefinition.revision(d),d.fields());assertEquals(r,DialogueAdminProtocol.response(DialogueAdminProtocol.encode(r)));}
    @Test void effectStatesBlockOnlyTheirDialogueAndResumeExactly()throws Exception{
        var settings=new DatabaseSettings(false,"","","");UUID player=UUID.randomUUID();
        try(var store=new DialogueStore(settings,dir.resolve("effects.db"))){
            // running 으로 시작한 기록: 실행 뒤 남은 동작만 기록하고, 다시 대기(queued)로 둘 수 있다.
            var first=store.transition(player,store.state(player),"elena:start:c0",List.of("story clue active","command give {player} diamond 1","quest patrol","custom gift 3"),true);
            assertEquals(List.of("command give {player} diamond 1","quest patrol","custom gift 3"),first.external());
            var effect=store.effects(player).getFirst();assertEquals("running",effect.status());assertEquals("elena",effect.dialogue());assertEquals(first.receipt(),effect.token());assertEquals(first.external(),effect.actions());
            assertFalse(store.move(player,first.receipt(),List.of("queued"),"running",null));
            assertTrue(store.move(player,first.receipt(),List.of("queued","running"),"queued",List.of("quest patrol","custom gift 3")));
            assertEquals(List.of("quest patrol","custom gift 3"),store.effects(player).getFirst().actions());assertEquals("queued",store.effects(player).getFirst().status());
            // 같은 대화의 다른 선택지는 막고, 다른 대화는 계속 진행한다.
            assertThrows(IllegalStateException.class,()->store.transition(player,store.state(player),"elena:next:c1",List.of("flag met yes")));
            var other=store.transition(player,store.state(player),"arden:start:c0",List.of("flag met yes","quest hunt"));assertFalse(other.receipt().isEmpty());assertEquals("yes",store.state(player).values().get("flag.met"));assertEquals(2,store.effects(player).size());assertEquals(2,store.audit(player).size());
            // 실패한 동작은 review 로 남고, 관리자가 다시 대기로 돌리거나 실행 없이 닫는다.
            assertTrue(store.move(player,first.receipt(),List.of("queued"),"running",List.of("custom gift 3")));assertTrue(store.move(player,first.receipt(),List.of("queued","running"),"review",List.of("custom gift 3")));
            assertFalse(store.move(player,first.receipt(),List.of("queued","running"),"done",List.of()));assertTrue(store.move(player,first.receipt(),List.of("review","running","pending"),"queued",null));assertEquals(List.of("custom gift 3"),store.effects(player).stream().filter(e->e.token().equals(first.receipt())).findFirst().orElseThrow().actions());
            store.finish(player,first.receipt());store.finish(player,other.receipt());assertTrue(store.effects(player).isEmpty());assertThrows(java.sql.SQLException.class,()->store.finish(player,other.receipt()));
            // 끝난 선택지는 다시 눌러도 외부 동작을 반복하지 않는다.
            assertTrue(store.transition(player,store.state(player),"elena:start:c0",List.of("command give {player} diamond 1"),true).external().isEmpty());
            // 종료 시 되돌리기: running 으로 기록만 하고 실행을 시작하지 못한 선택.
            var armed=store.transition(player,store.state(player),"elena:next:c1",List.of("command say hi"),true);assertTrue(store.requeue(player,"elena:next:c1"));assertFalse(store.requeue(player,"elena:next:c1"));assertEquals("queued",store.effects(player).getFirst().status());store.finish(player,armed.receipt());
        }
    }
    @Test void catalogSnapshotIsSkippedUntilARevisionChanges()throws Exception{
        try(var store=new DialogueStore(new DatabaseSettings(false,"","",""),dir.resolve("snapshot.db"))){
            var d=sample();assertTrue(store.edit(d.id(),"",d));var first=store.snapshot(null);assertNotNull(first);assertEquals(d,first.definitions().get(d.id()));assertEquals(DialogueDefinition.revision(d),first.revisions().get(d.id()));
            assertNull(store.snapshot(first.signature()));
            var f=new HashMap<>(d.fields());f.put("title","바뀐 제목");var edited=DialogueDefinition.fromFields(d.id(),f);assertTrue(store.edit(d.id(),first.revisions().get(d.id()),edited));
            var second=store.snapshot(first.signature());assertNotNull(second);assertNotEquals(first.signature(),second.signature());assertEquals("바뀐 제목",second.definitions().get(d.id()).title());assertEquals(DialogueDefinition.revision(edited),second.revisions().get(d.id()));assertNull(store.snapshot(second.signature()));
        }
    }
}
