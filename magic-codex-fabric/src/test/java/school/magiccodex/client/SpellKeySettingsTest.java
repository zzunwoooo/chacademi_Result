package school.magiccodex.client;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpellKeySettingsTest {
    @TempDir Path directory;
    @Test void sixSlotsOnlyAndIndependentDraft() {
        var saved=new SpellKeySettings(); var draft=saved.copy();
        draft.select(0,"flame"); draft.bind(0,49);
        assertTrue(saved.get(0).empty()); assertFalse(saved.sameAs(draft));
        assertThrows(IndexOutOfBoundsException.class,()->draft.select(6,"seventh"));
    }
    @Test void duplicateKeysAndSpellsAreRejectedWithoutMutation() {
        var s=new SpellKeySettings(); s.select(0,"flame"); s.bind(0,49); s.select(1,"wind"); s.bind(1,50);
        assertThrows(IllegalArgumentException.class,()->s.bind(1,49));
        assertThrows(IllegalArgumentException.class,()->s.select(1,"flame"));
        assertEquals(new SpellKeySettings.Binding("wind",50),s.get(1));
    }
    @Test void reservedAndUnknownKeysAreRejected() {
        var s=new SpellKeySettings(); s.select(0,"flame");
        for(int code:new int[]{87,65,83,68,256,999,-1})
            assertThrows(IllegalArgumentException.class,()->s.bind(0,code));
        assertFalse(s.complete());
    }
    @Test void supportsLettersNumbersSpaceAndPunctuation() {
        var s=new SpellKeySettings(); s.select(0,"flame");
        for(int code:new int[]{49,48,81,70,32,91,47,257}) { s.bind(0,code); assertEquals(code,s.get(0).keyCode()); }
    }
    @Test void clearingReleasesKeyAndSpell() {
        var s=new SpellKeySettings(); s.select(0,"flame"); s.bind(0,49); s.clear(0);
        s.select(1,"flame"); s.bind(1,49); s.clear();
        assertTrue(s.slots().stream().allMatch(SpellKeySettings.Binding::empty)); assertTrue(s.complete());
    }
    @Test void saveAndLoadPreserveOrderGapsAndActualKeys() throws Exception {
        var s=new SpellKeySettings(); s.select(0,"flame"); s.bind(0,70); s.select(5,"wind"); s.bind(5,81);
        Path file=directory.resolve("nested/settings.yml"); s.save(file);
        assertTrue(s.sameAs(SpellKeySettings.load(file)));
        s.clear(0); s.save(file); assertTrue(s.sameAs(SpellKeySettings.load(file)));
    }
    @Test void unfinishedDraftDoesNotDamagePreviousSave() throws Exception {
        Path file=directory.resolve("settings.yml"); var s=new SpellKeySettings(); s.save(file);
        String original=Files.readString(file); s.select(0,"flame");
        assertThrows(java.io.IOException.class,()->s.save(file)); assertEquals(original,Files.readString(file));
    }
    @Test void malformedOrDuplicateFileFailsWithoutRewriting() throws Exception {
        Path file=directory.resolve("settings.yml");
        for(String content:new String[]{"version: 2\nslots: []", "version: 1\nversion: 1\nslots: []",
                "version: 1\nslots: [{spell: fire, key: 49}, {spell: wind, key: 49}, null, null, null, null]",
                "version: 1\nslots: [{spell: fire, key: 49}, {spell: fire, key: 50}, null, null, null, null]"}) {
            Files.writeString(file,content); assertThrows(java.io.IOException.class,()->SpellKeySettings.load(file));
            assertEquals(content,Files.readString(file));
        }
    }
    @Test void oversizedFileRejected() throws Exception {
        Path file=directory.resolve("settings.yml"); Files.writeString(file," ".repeat(16385));
        assertThrows(java.io.IOException.class,()->SpellKeySettings.load(file));
    }
    @Test void legacyWBindingKeepsSpellAndOtherKeysWithoutRewritingFile() throws Exception {
        Path file=directory.resolve("old.yml");
        String content="version: 1\nslots: [{spell: fire, key: 87}, {spell: wind, key: 70}, null, null, null, null]";
        Files.writeString(file,content);
        var settings=SpellKeySettings.load(file);
        assertEquals("fire",settings.get(0).spellId());assertEquals(-1,settings.get(0).keyCode());
        assertEquals(new SpellKeySettings.Binding("wind",70),settings.get(1));
        assertFalse(settings.complete());assertEquals(content,Files.readString(file));
        settings.bind(0,49);settings.save(file);
        assertTrue(SpellKeySettings.load(file).sameAs(settings));
    }
    // owner 2026-10-17: only WASD is locked; former shortcut keys (O, P, X, I, Z) keep spells saved on them
    @Test void formerShortcutKeysKeepTheirSpells() throws Exception {
        Path file=directory.resolve("old-o.yml");
        Files.writeString(file,"version: 1\nslots: [{spell: fire, key: 79}, {spell: wind, key: 70}, null, null, null, null]");
        var settings=SpellKeySettings.load(file);
        assertEquals(new SpellKeySettings.Binding("fire",79),settings.get(0));
        assertEquals(new SpellKeySettings.Binding("wind",70),settings.get(1));
        for(int code:new int[]{73,79,80,88,90})assertTrue(KeySettingsLayout.allowed(code));
        assertFalse(KeySettingsLayout.reservedShortcut(79));assertTrue(KeySettingsLayout.reservedShortcut(87));
    }
    @Test void missingCatalogEntriesArePreservedForLaterReload() throws Exception {
        var s=new SpellKeySettings(); s.select(3,"temporarily_missing"); s.bind(3,82);
        Path file=directory.resolve("settings.yml"); s.save(file); assertEquals("temporarily_missing",SpellKeySettings.load(file).get(3).spellId());
    }
    @Test void keyboardAssignFillsFirstEmptySlotAndStopsAtSix() {
        var s=new SpellKeySettings();
        String[] ids={"a1","a2","a3","a4","a5","a6"}; int[] keys={49,50,51,53,54,82};
        for(int i=0;i<6;i++) assertEquals(i,s.assign(keys[i],ids[i]));
        assertEquals(6,s.count());
        var before=s.slots();
        var error=assertThrows(IllegalArgumentException.class,()->s.assign(70,"seventh"));
        assertTrue(error.getMessage().contains("6"));
        assertEquals(before,s.slots());
        assertThrows(IllegalArgumentException.class,()->s.assign(87,"seventh"));
    }
    @Test void assigningExistingKeyReplacesSpellInPlace() {
        var s=new SpellKeySettings(); s.assign(49,"flame"); s.assign(50,"wind");
        assertEquals(1,s.assign(50,"water"));
        assertEquals(new SpellKeySettings.Binding("water",50),s.get(1)); assertEquals(-1,s.slotOfSpell("wind"));
    }
    @Test void assigningRegisteredSpellToNewKeyMovesItWithoutDuplicate() {
        var s=new SpellKeySettings(); s.assign(49,"flame"); s.assign(50,"wind"); s.assign(51,"water");
        // Even with all six full, moving an already registered spell is allowed.
        s.assign(53,"a"); s.assign(54,"b"); s.assign(82,"c");
        assertEquals(2,s.assign(70,"water"));
        assertEquals(new SpellKeySettings.Binding("water",70),s.get(2)); assertEquals(-1,s.slotOfKey(51));
        // Registered spell onto another spell's key: target slot keeps order, old slot is freed.
        assertEquals(0,s.assign(49,"wind")); assertTrue(s.get(1).empty()); assertEquals(5,s.count());
    }
    @Test void keyNeverCastsAnotherSlotsSpell() {
        // Regression: a stale slot selection used to move slot 3's spell onto key 4.
        var s=new SpellKeySettings(); s.assign(49,"one"); s.assign(50,"two"); s.assign(51,"three");
        s.assign(52,"four");
        assertEquals("three",s.get(s.slotOfKey(51)).spellId()); assertEquals("four",s.get(s.slotOfKey(52)).spellId());
        var state=new CastingState(); state.setBindings(s);
        assertTrue(state.bound(52)); assertTrue(state.bound(51));
        assertEquals(1,state.bindings().stream().filter(b->b.keyCode()==52).count());
    }
    @Test void clearKeyAndSwapKeepSpellAndKeyTogether() throws Exception {
        var s=new SpellKeySettings(); s.assign(49,"flame"); s.assign(50,"wind"); s.assign(51,"water");
        s.swap(0,2);
        assertEquals(new SpellKeySettings.Binding("water",51),s.get(0)); assertEquals(new SpellKeySettings.Binding("flame",49),s.get(2));
        s.swap(1,5); assertTrue(s.get(1).empty()); assertEquals(new SpellKeySettings.Binding("wind",50),s.get(5));
        assertThrows(IndexOutOfBoundsException.class,()->s.swap(0,6));
        assertEquals(5,s.clearKey(50)); assertEquals(-1,s.clearKey(50)); assertEquals(2,s.count());
        Path file=directory.resolve("order.yml"); s.save(file); assertTrue(s.sameAs(SpellKeySettings.load(file)));
    }
    @Test void keyboardHasUniqueNonOverlappingTargets() {
        var keys=KeySettingsLayout.KEYS;
        assertEquals(keys.size(),keys.stream().map(KeySettingsLayout.Key::code).distinct().count());
        for(var key:keys) for(var other:keys) if(key!=other)
            assertFalse(key.box().contains(other.box().centerX(),other.box().centerY()));
        for(int i=0;i<6;i++) assertFalse(KeySettingsLayout.slot(i).contains(KeySettingsLayout.slotKey(i).centerX(),KeySettingsLayout.slotKey(i).centerY()));
    }

    @Test void registrationOrderSurvivesRemovalReplacementAndRestart() throws Exception {
        var s=new SpellKeySettings(); s.assign(49,"first"); s.assign(50,"second"); s.assign(51,"third");
        s.clearKey(50); s.assign(52,"fourth"); s.assign(49,"replacement");
        assertEquals(java.util.List.of("third","fourth","replacement"),s.orderedSlots().stream().filter(b->!b.empty()).map(SpellKeySettings.Binding::spellId).toList());
        s.assign(53,"third"); s.assign(54,"second");
        var file=directory.resolve("registration.yml"); s.save(file);
        var restored=SpellKeySettings.load(file);
        assertEquals(s.orderedSlots(),restored.orderedSlots());
        var casting=new CastingState(); casting.setBindings(restored);
        assertEquals("third",casting.bindings().getFirst().spellId());
        assertEquals(53,casting.bindings().getFirst().keyCode());
    }
    @Test void modifierKeysAreAssignable() {
        for(int code:new int[]{340,341,342,344,345,346})assertTrue(KeySettingsLayout.allowed(code));
    }
}
