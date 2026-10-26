package school.magiccodex.paper;
import java.util.*;import java.util.concurrent.*;import java.nio.file.*;import java.util.logging.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import org.bukkit.*;import org.bukkit.entity.*;
class NameplateTitlesTest {
 @TempDir Path temp;
 static final TitleDefinition PREFIX=new TitleDefinition("prefix_spell",0,"소환술",0x96E4EC,true,false),SUFFIX=new TitleDefinition("suffix_professor",1,"교수",0xDEC58E,true,false);
 @Test void lineUsesSlotOrderWithoutAnyAddedSpaceAndSuffixColor(){assertEquals("소환술교수",NameplateTitles.combined(List.of(PREFIX,SUFFIX)));assertEquals("소환술",NameplateTitles.combined(List.of(PREFIX)));assertEquals("교수",NameplateTitles.combined(List.of(SUFFIX)));assertEquals("",NameplateTitles.combined(List.of()));var runs=NameplateTitles.runs(List.of(PREFIX,SUFFIX));assertEquals(0xDEC58E,runs.get(0).color());assertEquals(0xDEC58E,runs.get(1).color());assertEquals("소환술 교수 황준우",TitleDefinition.composed("소환술","황준우","교수"),"existing chat/UI composition is untouched");}
 @Test void colorUsesActualSuffixEvenWhenOrderChangesAndKeepsPrefixWhenSuffixIsAbsent(){
  assertEquals(List.of(),NameplateTitles.runs(List.of()));
  assertEquals(PREFIX.color(),NameplateTitles.runs(List.of(PREFIX)).getFirst().color());
  assertEquals(SUFFIX.color(),NameplateTitles.runs(List.of(SUFFIX)).getFirst().color());
  var reversed=NameplateTitles.runs(List.of(SUFFIX,PREFIX));assertTrue(reversed.stream().allMatch(r->r.color()==SUFFIX.color()));
  var black=new TitleDefinition("suffix_black",1,"black",0,true,false);assertTrue(NameplateTitles.runs(List.of(PREFIX,black)).stream().allMatch(r->r.color()==0));
  var custom=new TitleDefinition("suffix_custom",1,"custom",0x123456,true,false);assertTrue(NameplateTitles.runs(List.of(PREFIX,custom)).stream().allMatch(r->r.color()==0x123456));
  assertEquals(0x96E4EC,PREFIX.color(),"shared catalog and chat color are not mutated");
 }
 @Test void nameLineRemovesOnlyExactEquippedBoundaryDecorations(){var slots=List.of(PREFIX,SUFFIX);assertEquals("황준우",NameplateTitles.plainName("소환술 교수 황준우",slots));assertEquals("황준우",NameplateTitles.plainName("소환술 황준우 교수",slots));assertEquals("황준우",NameplateTitles.plainName("황준우",slots));assertEquals("소환술사황준우",NameplateTitles.plainName("소환술사황준우",slots));assertEquals("교수",NameplateTitles.plainName("교수",slots));assertEquals("소환술 황준우",NameplateTitles.plainName("소환술 황준우",List.of()));}
 private TitleBridge catalog(){var service=mock(TitleBridge.class);when(service.registeredTitles(false)).thenReturn(Map.of(PREFIX.id(),PREFIX.name(),SUFFIX.id(),SUFFIX.name()));when(service.nameplateDefinition(PREFIX.id())).thenReturn(PREFIX);when(service.nameplateDefinition(SUFFIX.id())).thenReturn(SUFFIX);return service;}
 @Test void npcMappingResolvesCurrentDefinitionsAndInvalidOrWrongSideIdsFallBack(){var service=catalog();var label=new NameplateStore.Label("엘레나",PREFIX.id(),SUFFIX.id());assertEquals(List.of(PREFIX,SUFFIX),NameplateTitles.npc(label,service));assertEquals(List.of(),NameplateTitles.npc(new NameplateStore.Label("엘레나",SUFFIX.id(),"missing"),service));when(service.nameplateDefinition(SUFFIX.id())).thenReturn(null);assertEquals(List.of(PREFIX),NameplateTitles.npc(label,service));assertEquals(List.of(),NameplateTitles.npc(label,null));verify(service,never()).grant(any(),any(),anyBoolean(),any());}
 @Test void playerViewUsesSameOwnedEquippedCacheAndReflectsSwitchesAndDefinitionReload()throws Exception{
  var service=mock(TitleBridge.class,CALLS_REAL_METHODS);var catalogField=TitleBridge.class.getDeclaredField("catalog");catalogField.setAccessible(true);catalogField.set(service,Map.of(PREFIX.id(),PREFIX,SUFFIX.id(),SUFFIX));
  var cacheField=TitleBridge.class.getDeclaredField("cache");cacheField.setAccessible(true);Map<UUID,Object> cache=new ConcurrentHashMap<>();cacheField.set(service,cache);var cached=Class.forName(TitleBridge.class.getName()+"$Cached").getDeclaredConstructors()[0];cached.setAccessible(true);UUID id=UUID.randomUUID();
  assertEquals(List.of(),service.nameplateTitles(id));cache.put(id,cached.newInstance(new TitleStore.State(1,PREFIX.id(),SUFFIX.id(),Set.of(PREFIX.id(),SUFFIX.id())),"황준우"));assertEquals(List.of(PREFIX,SUFFIX),service.nameplateTitles(id));
  cache.put(id,cached.newInstance(new TitleStore.State(2,"",SUFFIX.id(),Set.of(PREFIX.id(),SUFFIX.id())),"황준우"));assertEquals(List.of(SUFFIX),service.nameplateTitles(id));
  var renamed=new TitleDefinition(SUFFIX.id(),1,"대마법사",0xff8844,true,false);catalogField.set(service,Map.of(SUFFIX.id(),renamed));assertEquals(List.of(renamed),service.nameplateTitles(id));
  cache.put(id,cached.newInstance(new TitleStore.State(3,"",SUFFIX.id(),Set.of()),"황준우"));assertEquals(List.of(),service.nameplateTitles(id),"no ownership is invented");
 }
 @Test void legacyRoleIsRetainedAsDataWithoutBecomingATitleOrGrantingOwnership()throws Exception{Path p=temp.resolve("nameplates.yml");Files.writeString(p,"labels:\n  citizens-abc:\n    name: 엘레나\n    title: 교수\n");var data=NameplateStore.load(p);assertEquals("교수",data.get("citizens-abc").legacyTitle());assertEquals("",data.get("citizens-abc").prefixId());assertEquals("",data.get("citizens-abc").suffixId());assertEquals(List.of(),NameplateTitles.npc(data.get("citizens-abc"),catalog()));NameplateStore.save(p,data);assertEquals(data,NameplateStore.load(p));}
 @Test void freeCommandsDoNotNeedCatalogAndSetClearsPreviousPrefix()throws Exception{
  var plugin=mock(MagicCodexBridge.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
  var bridge=spy(new NameplateBridge(plugin,false));var player=mock(Player.class);when(player.hasPermission("magiccodex.nameplates.admin")).thenReturn(true);var source=mock(io.papermc.paper.command.brigadier.CommandSourceStack.class);when(source.getSender()).thenReturn(player);var npc=new NameplateBridge.Npc(mock(Entity.class),"character-abc","엘레나");doReturn(npc).when(bridge).target(eq(player),anyString());
  var dispatcher=new com.mojang.brigadier.CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack>();dispatcher.register(io.papermc.paper.command.brigadier.Commands.literal("NPC관리").then(bridge.commandTree()));
  try(var b=mockStatic(Bukkit.class)){b.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
   dispatcher.execute("NPC관리 명찰 설정 근처 엘레나 학생지도교수",source);var saved=NameplateStore.load(temp.resolve("nameplates.yml")).get(npc.key());assertEquals("엘레나",saved.name());assertEquals("학생지도교수",saved.suffixText());assertEquals("",saved.prefixText());assertEquals("",saved.suffixId());
   dispatcher.execute("NPC관리 명찰 칭호 근처 앞 소환술",source);dispatcher.execute("NPC관리 명찰 칭호 근처 뒤 교수",source);saved=NameplateStore.load(temp.resolve("nameplates.yml")).get(npc.key());assertEquals("소환술교수",NameplateTitles.npcRuns(saved,null).stream().map(school.magiccodex.protocol.NameplateProtocol.TitleRun::text).collect(java.util.stream.Collectors.joining()));assertTrue(NameplateTitles.npcRuns(saved,null).stream().allMatch(x->x.color()==0xDEC58E));
   dispatcher.execute("NPC관리 명찰 설정 근처 \"엘레나 교수\" \"학생 지도 교수\"",source);saved=NameplateStore.load(temp.resolve("nameplates.yml")).get(npc.key());assertEquals("",saved.prefixText());assertEquals("학생 지도 교수",saved.suffixText());
   dispatcher.execute("NPC관리 명찰 칭호 근처 앞 미등록자유문구",source);dispatcher.execute("NPC관리 명찰 칭호 근처 앞 비우기",source);assertEquals("",NameplateStore.load(temp.resolve("nameplates.yml")).get(npc.key()).prefixText());
   var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse("NPC관리 명찰 칭호 근처 뒤 ",source)).get().getList();assertTrue(suggestions.stream().anyMatch(x->x.getText().equals("비우기")));assertFalse(suggestions.stream().anyMatch(x->x.getText().equals(SUFFIX.id())));
   var before=NameplateStore.load(temp.resolve("nameplates.yml"));assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,()->dispatcher.execute("NPC관리 명찰 칭호 근처 뒤 "+"가".repeat(49),source));assertEquals(before,NameplateStore.load(temp.resolve("nameplates.yml")));
   dispatcher.execute("NPC관리 명찰 조회 근처",source);verify(player).sendMessage(contains("학생 지도 교수"));dispatcher.execute("NPC관리 명찰 칭호 근처 초기화",source);saved=NameplateStore.load(temp.resolve("nameplates.yml")).get(npc.key());assertEquals("엘레나 교수",saved.name());assertTrue(NameplateTitles.npcRuns(saved,null).isEmpty());dispatcher.execute("NPC관리 명찰 초기화 근처",source);assertTrue(NameplateStore.load(temp.resolve("nameplates.yml")).isEmpty());
  }
 }
 @Test void legacyIdsResolveToDisplayTextWhileUnknownIdsAreRetainedAndNeverShown()throws Exception{
  var service=catalog();var old=new NameplateStore.Label("엘레나",PREFIX.id(),SUFFIX.id());var migrated=NameplateTitles.literal(old,service);assertEquals(PREFIX.name(),migrated.prefixText());assertEquals(SUFFIX.name(),migrated.suffixText());assertEquals("",migrated.prefixId());assertEquals("",migrated.suffixId());
  var unknown=new NameplateStore.Label("엘레나","missing",SUFFIX.id());var value=NameplateTitles.literal(unknown,service);assertEquals("missing",value.prefixId());assertEquals("",value.prefixText());assertEquals(List.of(SUFFIX.name()),NameplateTitles.npcRuns(value,service).stream().map(school.magiccodex.protocol.NameplateProtocol.TitleRun::text).toList());
  Path f=temp.resolve("nameplates.yml");NameplateStore.save(f,Map.of("character-abc",value));assertEquals(value,NameplateStore.load(f).get("character-abc"));verify(service,never()).grant(any(),any(),anyBoolean(),any());
 }
 @Test void freeTextValidatesUnicodeCodePointsAndPersistsWithoutCatalog()throws Exception{
  var label=new NameplateStore.Label("엘레나","","","","😀".repeat(48),"교수 & 연구자");NameplateStore.save(temp.resolve("free.yml"),Map.of("entity-abc",label));assertEquals(label,NameplateStore.load(temp.resolve("free.yml")).get("entity-abc"));assertEquals(2,NameplateTitles.npcRuns(label,null).size());assertThrows(IllegalArgumentException.class,()->new NameplateStore.Label("엘레나","","","","a".repeat(49),""));assertThrows(IllegalArgumentException.class,()->new NameplateStore.Label("엘레나","","","","","bad\ntext"));
 }
 @Test void unicodeAndQuotedNamesUseSameCommandGrammar()throws Exception{var arg=new NameplateArgument();assertEquals("근처",arg.parse(new com.mojang.brigadier.StringReader("근처 다음")));assertEquals("엘레나 교수",arg.parse(new com.mojang.brigadier.StringReader("\"엘레나 교수\" 다음")));assertEquals("황준우",arg.parse(new com.mojang.brigadier.StringReader("황준우")));}
}
