package school.magiccodex.paper;
import java.util.*;import java.util.function.Consumer;import java.util.logging.Logger;import org.junit.jupiter.api.Test;import org.mockito.ArgumentCaptor;import org.bukkit.*;import org.bukkit.command.CommandSender;import org.bukkit.entity.Player;import school.magiccodex.protocol.JournalProtocol;import school.magiccodex.protocol.JournalProtocol.*;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class JournalBridgeTest {
 @Test void serverStoryRejectsForgedCatalogAndAckAndDirectsAdminsToStageCommand(){fixture((bridge,plugin,quest,dialogue,target,admin)->{
  var row=new Entry("story","server-stage","Server chapter","","active",true,"","","Chapter 1");doReturn(List.of(row)).when(bridge).storyRows(target);
  var forged=new Entry("story","forged-stage","Forged chapter","","claimed",true,"","");
  bridge.onPluginMessageReceived(JournalProtocol.QUERY,target,JournalProtocol.encode(new Message(5,"catalog","","","",0,1,List.of(forged))));
  assertEquals(Map.of("story:server-stage","Server chapter"),bridge.catalog(target));
  assertThrows(IllegalArgumentException.class,()->bridge.command(admin,target,"부여","story:server-stage"));
  var refusal=assertThrows(IllegalArgumentException.class,()->bridge.command(admin,target,"주기","story:server-stage"));assertTrue(refusal.getMessage().contains("/유저관리 <닉네임> 스토리진행 단계 <단계>"));
  assertThrows(IllegalArgumentException.class,()->bridge.command(admin,target,"주기","story:forged-stage"));
  var ack=new Message(3,"forged-token","grant","server-stage","ok",0,0,List.of());
  bridge.onPluginMessageReceived(JournalProtocol.QUERY,player(),JournalProtocol.encode(ack));bridge.onPluginMessageReceived(JournalProtocol.QUERY,target,JournalProtocol.encode(ack));bridge.onPluginMessageReceived(JournalProtocol.QUERY,target,JournalProtocol.encode(ack));
  verify(admin,never()).sendMessage(anyString());verify(target,never()).sendPluginMessage(any(),anyString(),any());verify(quest,never()).journalEdit(any(),any(),anyString(),anyString());verify(dialogue,never()).journalEdit(any(),any(),anyString(),anyString());
 });}
 @Test void queryRequiresPermissionAndReturnsServerStoryRows(){fixture((bridge,plugin,quest,dialogue,target,admin)->{
  var row=new Entry("story","stage-2","Server stage","","active",true,"1 / 3","","Chapter 1");doReturn(List.of(row)).when(bridge).storyRows(target);
  var request=JournalProtocol.encode(new Message(0,"query-token","","","",0,0,List.of()));bridge.onPluginMessageReceived(JournalProtocol.QUERY,target,request);
  verify(quest,never()).journalRows(any(),any());verify(target,never()).sendPluginMessage(any(),anyString(),any());
  when(target.hasPermission("magiccodex.quests")).thenReturn(true);
  doAnswer(i->{((Consumer<List<Entry>>)i.getArgument(1)).accept(List.of());return null;}).when(quest).journalRows(eq(target),any());
  doAnswer(i->{((Consumer<List<Entry>>)i.getArgument(1)).accept(List.of());return null;}).when(dialogue).journalRows(eq(target),any());
  bridge.onPluginMessageReceived(JournalProtocol.QUERY,target,request);var packet=ArgumentCaptor.forClass(byte[].class);verify(target).sendPluginMessage(eq(plugin),eq(JournalProtocol.REPLY),packet.capture());
  var reply=JournalProtocol.decode(packet.getValue());assertEquals(1,reply.kind());assertEquals("query-token",reply.token());assertEquals(List.of(row),reply.entries());verify(target,times(2)).hasPermission("magiccodex.quests");verify(quest).journalRows(eq(target),any());verify(dialogue).journalRows(eq(target),any());
 });}
 @Test void questAndDialogueEditsRetainExistingServerOwners(){fixture((bridge,plugin,quest,dialogue,target,admin)->{
  doReturn(List.of()).when(bridge).storyRows(target);when(quest.journalCatalog()).thenReturn(Map.of("quest:q1","Quest"));when(dialogue.journalCatalog()).thenReturn(Map.of("dialogue:d1","Dialogue"));
  bridge.command(admin,target,"주기","quest:q1");bridge.command(admin,target,"초기화","dialogue:d1");verify(quest).journalEdit(admin,target,"q1","grant");verify(dialogue).journalEdit(admin,target,"d1","reset");verify(target,never()).sendPluginMessage(any(),anyString(),any());
 });}
 interface Check{void run(JournalBridge bridge,MagicCodexBridge plugin,QuestBridge quest,DialogueBridge dialogue,Player target,CommandSender admin);}
 private void fixture(Check check){var plugin=mock(MagicCodexBridge.class);var server=mock(Server.class,RETURNS_DEEP_STUBS);var quest=mock(QuestBridge.class);var dialogue=mock(DialogueBridge.class);when(plugin.questBridge()).thenReturn(quest);when(plugin.dialogueBridge()).thenReturn(dialogue);when(plugin.getLogger()).thenReturn(Logger.getLogger("journal-test"));when(quest.journalCatalog()).thenReturn(Map.of());when(dialogue.journalCatalog()).thenReturn(Map.of());var target=player();var admin=mock(CommandSender.class);when(admin.getName()).thenReturn("admin");var messenger=server.getMessenger();var manager=server.getPluginManager();var scheduler=server.getScheduler();try(var b=mockStatic(Bukkit.class)){b.when(Bukkit::getMessenger).thenReturn(messenger);b.when(Bukkit::getPluginManager).thenReturn(manager);b.when(Bukkit::getScheduler).thenReturn(scheduler);check.run(spy(new JournalBridge(plugin)),plugin,quest,dialogue,target,admin);}}
 private Player player(){var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.isOnline()).thenReturn(true);when(p.getListeningPluginChannels()).thenReturn(Set.of(JournalProtocol.REPLY));return p;}
}
