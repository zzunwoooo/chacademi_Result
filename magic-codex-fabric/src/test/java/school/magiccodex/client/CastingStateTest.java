package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static school.magiccodex.client.CastingState.Kind.*;

class CastingStateTest {
    @Test void serverManagedCastWaitsForApprovalAndUsesServerCooldown() {
        var s=state();press(s,90,0);var requested=new ArrayList<String>();
        assertEquals(PENDING,s.input(49,1,false,true,100,true,spell->requested.add(spell.id())).kind());
        assertEquals(List.of("magical_flame"),requested);assertEquals(0,s.remaining("magical_flame",100));
        s.input(49,0,false,true,101,true,spell->requested.add(spell.id()));
        assertEquals(PENDING,s.input(49,1,false,true,102,true,spell->requested.add(spell.id())).kind());
        assertEquals(1,requested.size());
        s.serverResult("magical_flame",7000,200);assertEquals(7000,s.remaining("magical_flame",200));
    }
    @Test void rejectedServerCastDoesNotStartCooldownAndCanBeRetried() {
        var s=state();press(s,90,0);
        s.input(49,1,false,true,100,true,spell->{});s.serverResult("magical_flame",0,200);
        s.input(49,0,false,true,201,true,spell->{});
        assertEquals(PENDING,s.input(49,1,false,true,202,true,spell->{}).kind());
        assertEquals(0,s.remaining("magical_flame",202));
    }
    private final List<String> sent=new ArrayList<>();
    private CastingState state() {
        var s=new CastingState(); s.catalog(CodexData.previewSpells());
        var b=new SpellKeySettings(); b.select(0,"magical_flame"); b.bind(0,49); b.select(1,"feather_step"); b.bind(1,82); s.setBindings(b);
        return s;
    }
    private CastingState.Result press(CastingState s,int key,long now) {
        s.input(key,0,key==90,true,now,sent::add);
        return s.input(key,1,key==90,true,now,sent::add);
    }
    @Test void offLeavesVanillaKeysAloneAndZEnablesCasting() {
        var s=state(); assertEquals(PASS,press(s,49,0).kind()); assertTrue(sent.isEmpty());
        assertEquals(MODE,press(s,90,0).kind()); assertTrue(s.enabled());
        assertEquals(CAST,press(s,49,100).kind()); assertEquals(List.of("cast magical_flame"),sent);
    }
    @Test void usesActualAssignedKeyInsteadOfSlotNumber() {
        var s=state(); press(s,90,0); assertEquals(PASS,press(s,50,0).kind());
        assertEquals(CAST,press(s,82,0).kind()); assertEquals("cast feather_step",sent.getFirst());
    }
    @Test void heldKeyAndRepeatedZAreOneActionPerPress() {
        var s=state(); press(s,90,0);
        assertEquals(CONSUME,s.input(90,2,true,true,100,sent::add).kind()); assertTrue(s.enabled());
        press(s,49,100); assertEquals(CONSUME,s.input(49,2,false,true,20000,sent::add).kind()); assertEquals(1,sent.size());
        assertEquals(CAST,press(s,49,20001).kind());
    }
    @Test void cooldownBlocksAtBoundaryAndTracksRemainingTime() {
        var s=state(); press(s,90,0); press(s,49,1000);
        assertEquals(10000,s.remaining("magical_flame",1000)); assertEquals(0.5f,s.fraction("magical_flame",6000));
        assertEquals(COOLDOWN,press(s,49,10999).kind()); assertEquals(CAST,press(s,49,11000).kind());
    }
    @Test void switchingModeCannotResetCooldown() {
        var s=state(); press(s,90,0); press(s,49,1000); press(s,90,2000);
        assertFalse(s.enabled()); assertEquals(8000,s.remaining("magical_flame",3000));
        press(s,90,4000); assertEquals(COOLDOWN,press(s,49,4001).kind());
    }
    @Test void reassigningKeyAndReloadingCatalogCannotResetCooldown() {
        var s=state(); press(s,90,0); press(s,49,1000);
        var b=new SpellKeySettings(); b.select(5,"magical_flame"); b.bind(5,70); s.setBindings(b);
        s.catalog(new ArrayList<>(CodexData.previewSpells()));
        assertEquals(PASS,press(s,49,2000).kind()); assertEquals(COOLDOWN,press(s,70,2000).kind());
    }
    @Test void cooldownsAreIndependentPerSpell() {
        var s=state(); press(s,90,0); press(s,49,1000); assertEquals(CAST,press(s,82,2000).kind());
        assertEquals(8000,s.remaining("magical_flame",3000)); assertEquals(14000,s.remaining("feather_step",3000));
    }
    @Test void unknownOrRevokedPermissionsNeverSendCommandButConsumeVanillaKey() {
        var s=state(); press(s,90,0);
        s.catalog(List.of(CodexData.previewSpells().getFirst().withPermission(null)));
        var pending=press(s,49,0); assertEquals(UNKNOWN,pending.kind()); assertTrue(pending.consumed());
        s.catalog(List.of(CodexData.previewSpells().getFirst().withPermission(false)));
        assertEquals(LOCKED,press(s,49,0).kind()); assertTrue(sent.isEmpty());
    }
    @Test void missingSpellOrCommandCannotInvokeAnything() {
        var s=state(); press(s,90,0); s.catalog(List.of()); assertEquals(MISSING,press(s,49,0).kind());
        var f=CodexData.previewSpells().getFirst();
        s.catalog(List.of(new CodexData.Spell(f.id(),f.name(),f.category(),f.description(),f.condition(),f.research(),10,true,f.icon(),f.permission(),"",0,true)));
        assertEquals(NO_COMMAND,press(s,49,0).kind()); assertTrue(sent.isEmpty());
    }
    @Test void chatMenusAndWorldAbsenceNeverToggleOrCast() {
        var s=state(); assertEquals(PASS,s.input(90,1,true,false,0,sent::add).kind()); assertFalse(s.enabled());
        press(s,90,0); assertEquals(PASS,s.input(49,1,false,false,0,sent::add).kind()); assertTrue(sent.isEmpty());
    }
    @Test void sendFailureDoesNotStartPresentationCooldown() {
        var s=state(); press(s,90,0);
        assertThrows(IllegalStateException.class,()->s.input(49,1,false,true,1000,command->{throw new IllegalStateException("connection closed");}));
        assertEquals(0,s.remaining("magical_flame",1000));
    }
    @Test void zeroCooldownStillRequiresSeparatePressesAndSendsExactYamlCommand() {
        var s=state(); var f=CodexData.previewSpells().getFirst();
        s.catalog(List.of(new CodexData.Spell(f.id(),f.name(),f.category(),f.description(),f.condition(),f.research(),0,true,f.icon(),f.permission(),"customcast flame example",0,true)));
        press(s,90,0); press(s,49,0); s.input(49,2,false,true,1,sent::add); press(s,49,2);
        assertEquals(List.of("customcast flame example","customcast flame example"),sent); assertEquals(0,s.remaining(f.id(),2));
    }
    @Test void reconnectResetsModeAndCooldownAndKeysStayRegistered() {
        var s=state(); press(s,90,0); press(s,49,0); s.reset();
        assertFalse(s.enabled()); assertEquals(0,s.remaining("magical_flame",1)); assertTrue(s.bound(49));
    }
    @Test void manaCostIsDisplayOnlyAndDoesNotBlockOrDeductOnClient() {
        var s=state(); var f=CodexData.previewSpells().getFirst();
        s.catalog(List.of(new CodexData.Spell(f.id(),f.name(),f.category(),f.description(),f.condition(),f.research(),0,true,f.icon(),f.permission(),"cast example",0,true,200)));
        press(s,90,0);
        assertEquals(CAST,press(s,49,0).kind());
        assertEquals(CAST,press(s,49,2).kind());
        assertEquals(List.of("cast example","cast example"),sent);
    }
    @Test void activePermissionScopeContainsOnlyRegisteredSpells() {
        var s=state(); assertEquals(List.of("magic.learned.magical_flame","magic.learned.feather_step"),s.permissions());
    }
    @Test void displayedCooldownNeverSaysZeroBeforeCompletion() {
        assertEquals("",SkillHudRenderer.countdown(0)); assertEquals("0.1",SkillHudRenderer.countdown(1));
        assertEquals("3.2",SkillHudRenderer.countdown(3150)); assertEquals("11",SkillHudRenderer.countdown(10001));
    }
}
