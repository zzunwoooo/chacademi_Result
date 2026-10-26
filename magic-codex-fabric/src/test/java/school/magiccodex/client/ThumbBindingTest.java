package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ThumbBindingTest {
 @TempDir Path temp;
 @Test void twoThumbsAndAllModifierKeysAreAssignable()throws Exception{
  for(int key:List.of(340,341,342,344,345,346,1003,1004))assertTrue(KeySettingsLayout.allowed(key));
  for(int key:List.of(87,65,83,68,0,1,2,1000,1001,1002,1005))assertFalse(KeySettingsLayout.allowed(key));
  assertEquals(1004,KeySettingsLayout.mouseCode(3));assertEquals(1003,KeySettingsLayout.mouseCode(4)); assertEquals(4,KeySettingsLayout.mouseButton(1003)); assertEquals(3,KeySettingsLayout.mouseButton(1004));
  for(int key:List.of(0,1,2,5))assertEquals(-1,KeySettingsLayout.mouseCode(key));
  var s=new SpellKeySettings();s.assign(49,"magical_flame");s.assign(340,"feather_step");s.assign(341,"wind_push");s.assign(1003,"fire_ball");s.assign(1004,"ice_ball");
  Path p=temp.resolve("keys.yml");s.save(p);assertTrue(s.sameAs(SpellKeySettings.load(p)));
  var copy=s.copy();copy.swap(0,4);assertFalse(copy.sameAs(s));assertEquals(49,s.get(0).keyCode());
 }
 @Test void thumbUsesSameModePermissionRepeatAndGuiGate(){
  var s=new CastingState();s.catalog(CodexData.previewSpells());var b=new SpellKeySettings();b.assign(1003,"magical_flame");s.setBindings(b);var calls=new ArrayList<String>();
  assertEquals(CastingState.Kind.PASS,s.input(1003,1,false,true,0,calls::add).kind());
  s.input(90,1,true,true,1,calls::add);
  assertEquals(CastingState.Kind.PASS,s.input(1003,1,false,false,2,calls::add).kind());assertTrue(calls.isEmpty());
  assertEquals(CastingState.Kind.CAST,s.input(1003,1,false,true,3,calls::add).kind());
  assertEquals(CastingState.Kind.CONSUME,s.input(1003,1,false,true,4,calls::add).kind());assertEquals(1,calls.size());
  s.input(1003,0,false,false,5,calls::add);assertEquals(CastingState.Kind.PASS,s.input(1003,1,false,false,6,calls::add).kind());assertEquals(1,calls.size());
 }
}
