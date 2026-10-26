package school.magiccodex.client;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SkillKeyPriorityTest {
 private CastingState state(int k){var s=new CastingState();s.alwaysActive();s.catalog(CodexData.previewSpells());var b=new SpellKeySettings();b.select(0,"magical_flame");b.bind(0,k);s.setBindings(b);return s;}
 @Test void hotbarConflictStaysConsumedDuringRepeatAndCooldown(){var s=state(49);assertTrue(s.input(49,1,false,true,0,x->{}).consumed());assertTrue(s.input(49,2,false,true,1,x->{}).consumed());s.input(49,0,false,true,2,x->{});assertEquals(CastingState.Kind.COOLDOWN,s.input(49,1,false,true,3,x->{}).kind());}
 @Test void heldKeyNeverConsumesTextOrKeyRegistrationInput(){var s=state(84);s.input(84,1,false,true,0,x->{});for(int a:new int[]{2,1,0})assertFalse(s.input(84,a,false,false,1,x->{fail("GUI cast");}).consumed());}
 @Test void inactivePresetKeysPassAndCooldownSurvives(){var s=state(49);s.input(49,1,false,true,100,x->{});s.releaseInputs();var b=new SpellKeySettings();b.select(0,"magical_flame");b.bind(0,50);s.setBindings(b);assertFalse(s.bound(49));assertTrue(s.bound(50));assertFalse(s.input(49,1,false,true,101,x->{}).consumed());assertEquals(CastingState.Kind.COOLDOWN,s.input(50,1,false,true,102,x->{}).kind());}
 @Test void combatKeysReplaceNormalWithoutOverwritingNormal(){var s=state(49);var n=new SpellKeySettings();n.select(0,"magical_flame");n.bind(0,49);var b=new SpellKeySettings();b.select(0,"feather_step");b.bind(0,82);s.setBindingsDirect(b.orderedSlots());assertFalse(s.bound(49));assertTrue(s.bound(82));s.setBindings(n);assertTrue(s.bound(49));assertFalse(s.bound(82));}
 @Test void unusedAndReleasedKeysPass(){var s=state(49);assertFalse(s.input(50,1,false,true,0,x->{}).consumed());assertFalse(s.input(49,0,false,true,0,x->{}).consumed());}
 @Test void thumbConflictConsumedButOtherMouseButtonsPass(){var s=state(KeySettingsLayout.THUMB_1);assertTrue(s.input(KeySettingsLayout.mouseCode(4),1,false,true,0,x->{}).consumed());assertFalse(s.input(KeySettingsLayout.mouseCode(3),1,false,true,0,x->{}).consumed());assertEquals(-1,KeySettingsLayout.mouseCode(0));}
}
