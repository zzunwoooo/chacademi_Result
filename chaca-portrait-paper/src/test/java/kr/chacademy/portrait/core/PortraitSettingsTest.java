package kr.chacademy.portrait.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PortraitSettingsTest {
    private PortraitSettings settings(Map<String, String> environment) {
        var config = new YamlConfiguration();
        config.set("openai.api-key", "config-test");
        return new PortraitSettings(config, environment::get);
    }
    @Test void configKeyIsUsedWithoutEnvironmentKeys() {
        assertEquals("config-test", settings(Map.of()).apiKey);
    }
    @Test void portraitEnvironmentKeyTakesPriority() {
        assertEquals("portrait-test", settings(Map.of(
                "CHACAPORTRAIT_OPENAI_KEY", " portrait-test ",
                "CHACANPC_OPENAI_KEY", "npc-test")).apiKey);
    }
    @Test void blankPortraitKeyFallsBackToNpcEnvironment() {
        assertEquals("npc-test", settings(Map.of(
                "CHACAPORTRAIT_OPENAI_KEY", " ",
                "CHACANPC_OPENAI_KEY", " npc-test ")).apiKey);
        assertEquals("config-test", settings(Map.of("CHACANPC_OPENAI_KEY", " ")).apiKey);
    }
    @Test void missingAndBlankConfigKeysRemainDisabled() {
        var config = new YamlConfiguration();
        assertFalse(new PortraitSettings(config, name -> null).hasKey());
        config.set("openai.api-key", "  ");
        assertFalse(new PortraitSettings(config, name -> " ").hasKey());
    }
    @Test void configKeyIsTrimmed() {
        var config = new YamlConfiguration();
        config.set("openai.api-key", " config-test ");
        assertEquals("config-test", new PortraitSettings(config, name -> null).apiKey);
    }
    @Test void packagedConfigLoadsBothModelPrices() throws Exception {
        var config = new YamlConfiguration();
        try (var reader = new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        var settings = new PortraitSettings(config, name -> null);
        var cost = new CostModel(settings.prices, settings.estimate, settings.estimate25);
        assertEquals("gpt-image-2.5-sunburst", settings.imageModel);
        for (String model : java.util.List.of("gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            assertTrue(cost.hasPrices(model));
            assertEquals(5, cost.prices(model).textInput());
            assertEquals(8, cost.prices(model).imageInput());
            assertEquals(2, cost.prices(model).cachedImageInput());
            assertEquals(30, cost.prices(model).imageOutput());
            assertEquals(429000, cost.reserveImage(model, "medium"));
        }
        assertTrue(cost.hasPrices("gpt-image-2"));
        assertTrue(cost.hasPrices("gpt-image-1.5"));
        assertEquals(156_300, cost.reserveImage("gpt-image-1.5", "medium"));
    }
    @Test void automaticGenerationIsOffByDefaultAndSchoolOnly() {
        var c = new YamlConfiguration();
        var initial = new PortraitSettings(c, name -> null);
        assertTrue(initial.enabled);
        assertFalse(initial.automaticGenerationAllowed());
        c.set("auto.first-join", true);
        c.set("server-id", "school");
        assertTrue(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
        c.set("server-id", "wild");
        assertFalse(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
        c.set("auto.first-join", false);
        assertTrue(new PortraitSettings(c, name -> null).enabled); // manual generation remains available
        c.set("server-id", "school"); c.set("auto.first-join", true); c.set("enabled", false);
        assertFalse(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
    }
    @Test void automaticGenerationServerListDefaultsToSchoolAndTutorialServers() {
        var c = new YamlConfiguration();
        c.set("auto.first-join", true);
        c.set("server-id", "tutorial");
        assertEquals(java.util.List.of("school", "tutorial"), new PortraitSettings(c, name -> null).autoServerIds);
        assertTrue(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
        c.set("server-id", "tutorial_x");
        assertFalse(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
        c.set("auto.server-ids", java.util.List.of("school", " tutorial_x ", "bad id!"));
        var tutorial = new PortraitSettings(c, name -> null);
        assertEquals(java.util.List.of("school", "tutorial_x"), tutorial.autoServerIds);
        assertTrue(tutorial.automaticGenerationAllowed());
        c.set("server-id", "wild");
        assertFalse(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
        c.set("auto.server-ids", java.util.List.of());
        c.set("server-id", "school");
        assertFalse(new PortraitSettings(c, name -> null).automaticGenerationAllowed());
    }
    @Test void blankServerIdFallsBackToSchoolButIsReportedAsUnset() {
        var c = new YamlConfiguration();
        var unset = new PortraitSettings(c, name -> null);
        assertFalse(unset.serverIdSet);
        assertEquals("school", unset.serverId);
        c.set("server-id", "  ");
        assertFalse(new PortraitSettings(c, name -> null).serverIdSet);
        c.set("server-id", " wild ");
        var wild = new PortraitSettings(c, name -> null);
        assertTrue(wild.serverIdSet);
        assertEquals("wild", wild.serverId);
    }
    @Test void packagedConfigShipsEmptyServerIdAndRerollPolicyMessages() throws Exception {
        var config = new YamlConfiguration();
        try (var reader = new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        var settings = new PortraitSettings(config, name -> null);
        assertFalse(settings.serverIdSet);
        String notice = "생성 시도가 실패할 경우에도 아이템은 사라집니다. 관련 문의 사항은 관리자를 찾아주세요.";
        assertTrue(settings.message("reroll-notice").contains(notice));
        assertTrue(settings.message("reroll-consumed").contains("리롤 아이템은 사용 처리되었습니다"));
        assertEquals("§b[일러스트] §f다시 그리기 대기 3번째예요. 약 5분 뒤에 완성돼요. 다 되면 알려드릴게요.",
                settings.message("reroll-queued", "position", 3, "minutes", 5));
        // 운영 중인 옛 config.yml(새 키 없음)에서도 빈 안내가 나가지 않는다
        var old = new PortraitSettings(new YamlConfiguration(), name -> null);
        assertTrue(old.message("reroll-notice").contains(notice));
        assertFalse(old.message("reroll-consumed").isEmpty());
        assertFalse(old.message("reroll-unavailable").isEmpty());
        assertFalse(old.message("budget").isEmpty());
    }
}
