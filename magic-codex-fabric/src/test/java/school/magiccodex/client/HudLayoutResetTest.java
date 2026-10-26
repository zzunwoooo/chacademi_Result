package school.magiccodex.client;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class HudLayoutResetTest{
 @TempDir Path dir;
 private HudLayoutStore layout(){var s=new HudLayoutStore();s.locked(false);s.place("menu",new HudLayoutStore.Box(20,20,180,60),800,600,550,450,1.6f);s.place("quest",new HudLayoutStore.Box(20,40,200,80),800,600,500,400,.7f);return s;}
 private boolean reset(boolean accepted,HudLayoutStore s,PlayerHudSettings size,Path layouts,Path scale){return HudLayoutReset.apply(accepted,s,()->{},()->{size.percent(PlayerHudSettings.DEFAULT);return size.save(scale);},()->s.save(layouts));}
 @Test void cancellingConfirmationLeavesMemoryAndEveryFileUntouched()throws Exception{
  var s=layout();var size=new PlayerHudSettings();size.percent(92);Path l=dir.resolve("hud-layouts.properties"),g=dir.resolve("gui-scale.properties");assertTrue(s.save(l));assertTrue(size.save(g));byte[] lb=Files.readAllBytes(l),gb=Files.readAllBytes(g);var menu=s.value("menu");var calls=new AtomicInteger();
  assertFalse(HudLayoutReset.apply(false,s,calls::incrementAndGet,()->{calls.incrementAndGet();return false;},()->{calls.incrementAndGet();return false;}));
  assertEquals(0,calls.get());assertEquals(menu,s.value("menu"));assertEquals(92,size.percent());assertArrayEquals(lb,Files.readAllBytes(l));assertArrayEquals(gb,Files.readAllBytes(g));assertFalse(s.locked());
 }
 @Test void confirmedResetRestoresAllPositionsAndSizesImmediatelyAndKeepsLock()throws Exception{
  var s=layout();var size=new PlayerHudSettings();size.percent(0);assertTrue(reset(true,s,size,dir.resolve("hud-layouts.properties"),dir.resolve("gui-scale.properties")));assertNull(s.value("menu"));assertNull(s.value("quest"));assertEquals(50,size.percent());assertTrue(size.visible());assertFalse(s.locked());var base=new HudLayoutStore.Box(20,20,180,60);assertEquals(base,s.fit("menu",base,800,600));
 }
 @Test void restartLoadsDefaultsAndOtherPreferencesAndFilesStayIntact()throws Exception{
  Path g=dir.resolve("gui-scale.properties"),l=dir.resolve("hud-layouts.properties");Files.writeString(g,"future-setting=keep\nopacity=.35\nplayer-hud-size=89\n");var s=layout();var size=PlayerHudSettings.load(g);Map<Path,byte[]> others=new HashMap<>();for(String name:List.of("key-settings.yml","key-settings-preset2.yml","normal-preset.properties","chacademi-chat-layout.json","chat-history.json","whisper-tabs.json")){Path f=dir.resolve(name);byte[] b=("preserve:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(f,b);others.put(f,b);}
  assertTrue(reset(true,s,size,l,g));var reloaded=HudLayoutStore.load(l);assertNull(reloaded.value("menu"));assertNull(reloaded.value("quest"));assertFalse(reloaded.locked());assertEquals(50,PlayerHudSettings.load(g).percent());var p=new Properties();try(var reader=Files.newBufferedReader(g)){p.load(reader);}assertEquals("keep",p.getProperty("future-setting"));assertEquals(".35",p.getProperty("opacity"));for(var e:others.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(e.getKey()));
 }
 @Test void resetRecoversCorruptOffscreenSavedLayouts()throws Exception{
  Path l=dir.resolve("hud-layouts.properties");Files.writeString(l,"locked=true\nmenu.x=999\nmenu.y=-999\nmenu.scale=2.5\nquest.x=NaN\nquest.y=1\nquest.scale=1\n");var s=HudLayoutStore.load(l);assertTrue(reset(true,s,new PlayerHudSettings(),l,dir.resolve("gui-scale.properties")));var b=s.fit("menu",new HudLayoutStore.Box(900,900,120,60),320,200);assertEquals(200,b.x());assertEquals(140,b.y());assertEquals(120,b.width());assertNull(HudLayoutStore.load(l).value("menu"));assertTrue(s.locked());
 }
 @Test void savingFailureIsReportedAndBothLayoutAndSizePersistenceAreAttempted(){var s=layout();var calls=new AtomicInteger();assertFalse(HudLayoutReset.apply(true,s,()->{},()->{calls.incrementAndGet();return false;},()->{calls.incrementAndGet();return true;}));assertEquals(2,calls.get());assertNull(s.value("menu"));}
}
