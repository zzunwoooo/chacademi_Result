package school.magiccodex.client;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class SpellYamlLoaderTest {
    @TempDir Path directory;
    private final SpellYamlLoader loader = new SpellYamlLoader();
    private static final String VALID = """
            id: magical_flame
            name: 작은 불씨
            description: |-
              손끝에서 불씨를 피웁니다.
              두 번째 줄입니다.
            category: fire
            icon: magiccodex:textures/spells/magical_flame.png
            permission: magic.learned.magical_flame
            discovery:
              description: 불이 붙은 상태로 30초 버티기
            cast:
              command: cast magicalflame
              cooldown-seconds: 10
            order: 20
            """;

    @Test void readsKoreanMultilineAndOptionalDefaultsWithoutInventingDiscovery() {
        var spell = loader.parse(VALID);
        assertEquals("작은 불씨", spell.name());
        assertEquals("손끝에서 불씨를 피웁니다.\n두 번째 줄입니다.", spell.description());
        assertEquals(CodexData.Category.FIRE, spell.category());
        assertEquals("", spell.research());
        assertEquals("cast magicalflame", spell.command());
        assertEquals(10, spell.cooldown());
        assertEquals(0, spell.manaCost());
        assertFalse(spell.discovered());
        assertFalse(spell.permissionKnown());
    }

    @Test void blankIconsAndFractionalCooldownAreExplicitlySupported() {
        var spell=loader.parse(VALID.replace("icon: magiccodex:textures/spells/magical_flame.png","icon: ''")
            .replace("cooldown-seconds: 10","cooldown-seconds: 1.5"));
        assertEquals("",spell.icon());assertEquals(1.5,spell.cooldown());
    }

    @Test void reviewedMissingIconsResolveForLegacyBlankConfigurations() throws Exception {
        for(String id:SpellYamlLoader.REVIEWED_ICON_IDS){
            var source=VALID.replace("magical_flame",id).replace("icon: magiccodex:textures/spells/"+id+".png","icon: ''");
            var spell=loader.parse(source);
            assertEquals("magiccodex:textures/spells/reviewed_20261016/"+id+".png",spell.icon());
            assertEquals(10,spell.cooldown());
            try(var in=getClass().getResourceAsStream("/assets/magiccodex/textures/spells/reviewed_20261016/"+id+".png")){
                assertNotNull(in,id);byte[] signature=in.readNBytes(8);
                assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},signature);
            }
        }
        assertEquals(8,SpellYamlLoader.REVIEWED_ICON_IDS.size());
    }
    @Test void reviewedIconsNeverOverrideExplicitUserSelection(){
        var spell=loader.parse(VALID.replace("magical_flame","pressure_lance"));
        assertEquals("magiccodex:textures/spells/pressure_lance.png",spell.icon());
    }

    @Test void readsManaCostAndPreservesItAcrossPermissionUpdates() {
        var spell = loader.parse(VALID.replace("cooldown-seconds: 10", "cooldown-seconds: 10\n  mana-cost: 180"));
        assertEquals(180, spell.manaCost());
        assertEquals(180, spell.withPermission(true).manaCost());
        assertEquals(180, spell.withPermission(false).manaCost());
        assertEquals(180, spell.withPermission(null).manaCost());
        assertEquals(0, loader.parse(VALID.replace("cooldown-seconds: 10", "mana-cost: 0")).manaCost());
        assertEquals(1000000, loader.parse(VALID.replace("cooldown-seconds: 10", "mana-cost: 1000000")).manaCost());
    }

    @Test void retiredMetadataIsIgnoredWithoutChangingPermissionsOrCosts() {
        var plain=loader.parse(VALID);
        for(String old:List.of("rank: 고급\ncircle: 9\n","rank: 초급\ncircle: null\n","circle: 0\n")) {
            var legacy=loader.parse(VALID+old);
            assertEquals(plain,legacy);
            assertEquals(plain.withPermission(true),legacy.withPermission(true));
        }
        assertTrue(java.util.Arrays.stream(CodexData.Spell.class.getRecordComponents())
                .noneMatch(c->c.getName().equals("rank") || c.getName().equals("circle")));
    }

    @Test void finalCatalogLoads237AfterRetiringUndevelopedSpells() {
        // 2026-10-20 owner: undeveloped 168~299 spells were dropped; 239~250 are the new dungeon gimmick spells.
        var result=loader.load(Path.of("src/main/resources/assets/magiccodex/default-spells"));
        assertTrue(result.success(),result.errors().toString());
        assertEquals(237,result.spells().size());
        var slots=result.spells().stream().collect(java.util.stream.Collectors.groupingBy(CodexData.Spell::order,
                java.util.stream.Collectors.mapping(CodexData.Spell::id,java.util.stream.Collectors.toSet())));
        assertEquals(237,slots.size());assertEquals(237,result.spells().stream().map(CodexData.Spell::id).distinct().count());
        var retired=java.util.Set.of(171,187,191,197,199,200,201,202,204,205,206,207,208,209);
        for(int order=1;order<=250;order++){
            if(retired.contains(order)||order>=215&&order<=238)assertFalse(slots.containsKey(order),"retired slot "+order);
            else{assertTrue(slots.containsKey(order),"missing slot "+order);assertEquals(1,slots.get(order).size());}
        }
        for(int order=300;order<=324;order++)assertTrue(slots.containsKey(order),"missing slot "+order);
        for(var spell:result.spells()){
            assertFalse(spell.condition().isBlank());assertFalse(spell.permissionKnown());assertFalse(spell.discovered());
        }
    }

    @Test void reloadReplacesSelectedManaCost() throws Exception {
        var file = directory.resolve("spell.yml");
        Files.writeString(file, VALID.replace("cooldown-seconds: 10", "mana-cost: 50"));
        var state = new CodexState(loader.load(directory).spells());
        state.selectSlot(0);
        assertEquals(50, state.selected().manaCost());
        Files.writeString(file, VALID.replace("cooldown-seconds: 10", "mana-cost: 90"));
        state.replaceSpells(loader.load(directory).spells());
        assertEquals(90, state.selected().manaCost());
    }

    static Stream<String> invalidFiles() {
        return Stream.of(
                VALID + "name: 중복 키\n",
                VALID.replace("category: fire", "category: all"),
                VALID.replace("category: fire", "category: typo"),
                VALID.replace("name: 작은 불씨", "name: true"),
                VALID.replace("cooldown-seconds: 10", "cooldown-seconds: -1"),
                VALID.replace("cooldown-seconds: 10", "cooldown-seconds: .nan"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: -1"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: 1.5"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: '50'"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: true"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: null"),
                VALID.replace("cooldown-seconds: 10", "mana-cost: 1000001"),
                VALID.replace("permission: magic.learned.magical_flame", "permission: ''"),
                VALID.replace("textures/spells/", "textures/../"),
                VALID.replace("command: cast", "command: /cast"),
                VALID + "learned: true\n",
                VALID.replace("  command:", "\tcommand:"),
                "!!java.lang.ProcessBuilder {}"
        );
    }
    @ParameterizedTest @MethodSource("invalidFiles")
    void rejectsMalformedAndAmbiguousConfiguration(String source) {
        assertThrows(RuntimeException.class, () -> loader.parse(source));
    }

    @Test void sortsByOrderThenIdAndRemovesDeletedFiles() throws Exception {
        Files.writeString(directory.resolve("가.yml"), VALID);
        Files.writeString(directory.resolve("나.yaml"), VALID.replace("id: magical_flame", "id: second").replace("order: 20", "order: 10"));
        Files.writeString(directory.resolve("ignored.txt"), "not yaml");
        var result = loader.load(directory);
        assertTrue(result.success(), result.errors().toString());
        assertEquals(List.of("second", "magical_flame"), result.spells().stream().map(CodexData.Spell::id).toList());
        Files.delete(directory.resolve("가.yml"));
        assertEquals(1, loader.load(directory).spells().size());
    }

    @Test void duplicateIdsRejectWholeBatchAndIncludeFilename() throws Exception {
        Files.writeString(directory.resolve("a.yml"), VALID);
        Files.writeString(directory.resolve("b.yml"), VALID);
        var result = loader.load(directory);
        assertFalse(result.success());
        assertTrue(result.spells().isEmpty());
        assertTrue(result.errors().getFirst().contains("b.yml"));
        assertTrue(result.errors().getFirst().contains("중복 id"));
    }

    @Test void invalidFileDoesNotReturnAPartialCatalog() throws Exception {
        Files.writeString(directory.resolve("valid.yml"), VALID);
        Files.writeString(directory.resolve("broken.yml"), "name: [");
        var result = loader.load(directory);
        assertFalse(result.success());
        assertTrue(result.spells().isEmpty());
        assertTrue(result.errors().getFirst().contains("broken.yml"));
    }

    @Test void firstRunInstalls323PreservesUserEditsAndRestoresMissingDefaults() throws Exception {
        Path spells = directory.resolve("spells");
        loader.installDefaults(spells);
        var result = loader.load(spells);
        assertTrue(result.success(), result.errors().toString());
        assertEquals(300, result.spells().size()); // 2026-10-25: 300 numbered spells (tools/spell_order.py)
        assertTrue(result.spells().stream().noneMatch(CodexData.Spell::permissionKnown));
        Files.writeString(spells.resolve("001_feather_fall.yml"), "user contents");
        Files.delete(spells.resolve("015_wind_basket.yml"));
        loader.installDefaults(spells);
        assertEquals("user contents", Files.readString(spells.resolve("001_feather_fall.yml")));
        assertTrue(Files.exists(spells.resolve("015_wind_basket.yml")));
    }

    @Test void rejectsOversizedFiles() throws Exception {
        Files.writeString(directory.resolve("oversized.yml"), "#".repeat(65537));
        assertFalse(loader.load(directory).success());
    }
    @Test void loadsThreeHundredSpellsAndStillBoundsTheCatalog() throws Exception {
        for(int i=0;i<300;i++)Files.writeString(directory.resolve("spell_"+i+".yml"),VALID.replace("id: magical_flame","id: spell_"+i));
        var loaded=loader.load(directory);
        assertTrue(loaded.success(),loaded.errors().toString());assertEquals(300,loaded.spells().size());
        for(int i=300;i<=SpellYamlLoader.MAX_SPELLS;i++)Files.writeString(directory.resolve("spell_"+i+".yml"),VALID.replace("id: magical_flame","id: spell_"+i));
        var oversized=loader.load(directory);
        assertFalse(oversized.success());assertTrue(oversized.spells().isEmpty());
    }

    @Test void unknownPermissionsAreNotTreatedAsUndiscovered() {
        var state = new CodexState(List.of(loader.parse(VALID)));
        assertEquals(1, state.visible().size());
        state.setFilter(CodexState.Filter.UNDISCOVERED);
        assertTrue(state.visible().isEmpty());
        state.setFilter(CodexState.Filter.DISCOVERED);
        assertTrue(state.visible().isEmpty());
    }

    @Test void catalogReplacementRefreshesSelectionAndClampsPageAfterDeletion() {
        var state = new CodexState(CodexData.previewSpells());
        state.selectSlot(0);
        state.replaceSpells(List.of(loader.parse(VALID.replace("name: 작은 불씨", "name: 수정한 불씨"))));
        assertEquals("수정한 불씨", state.selected().name());
        state.replaceSpells(CodexData.previewSpells());
        state.changePage(1);
        state.selectSlot(0);
        state.replaceSpells(List.of(loader.parse(VALID)));
        assertEquals(0, state.page());
        assertNull(state.selected());
    }

    @Test void mobSpellsKeepTheirRuntime300To323IdsWithoutRenumbering() {
        var result=loader.load(Path.of("src/main/resources/assets/magiccodex/default-spells"));assertTrue(result.success());
        var ids=List.of("fox_orbit","curving_plumes","crystal_orbit","venom_spit","smoke_veil","domain_stomp","seal_circle","seal_talismans","shield_slam","chain_arrow","mana_drain","soul_scythe","oblivion_orbs","bone_grasp","shadow_leap","binding_pull","fairy_dust","moon_wind","gale_wings","feather_storm","hunting_pounce","roar_leap","gravity_slam","tail_spikes");
        for(int i=0;i<24;i++){final String id=ids.get(i);var spell=result.spells().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();assertEquals(300+i,spell.order());assertEquals(id,spell.id());}
    }
}
