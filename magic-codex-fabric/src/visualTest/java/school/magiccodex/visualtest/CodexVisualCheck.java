package school.magiccodex.visualtest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.CodexData.Category;
import school.magiccodex.client.CodexHitboxes;
import school.magiccodex.client.CodexHitboxes.Rect;
import school.magiccodex.client.CodexLayout;
import school.magiccodex.client.CodexScreen;
import school.magiccodex.client.CodexCatalog;
import school.magiccodex.client.KeySettingsScreen;
import school.magiccodex.client.KeySettingsLayout;
import school.magiccodex.client.SpellKeySettings;
import school.magiccodex.client.CodexData;

/** Exercise real screen events and rendering without a world or desktop input. */
public final class CodexVisualCheck implements ClientModInitializer {
    private int ticks;
    private int phase;

    @Override
    public void onInitializeClient() {
        if(Boolean.getBoolean("magiccodex.spellShowcase")){school.magiccodex.client.SpellShowcaseClient.register();return;}
        if(Boolean.getBoolean("magiccodex.titleLiveCheck")){school.magiccodex.client.TitleLiveCheck.register();return;}
        if(Boolean.getBoolean("magiccodex.dialogueLiveCheck")){school.magiccodex.client.DialogueLiveCheck.register();return;}
        if(Boolean.getBoolean("magiccodex.dialogueCheck")){school.magiccodex.client.DialogueVisualCheck.register();return;}
        if(Boolean.getBoolean("magiccodex.questLiveCheck")){school.magiccodex.client.QuestLiveCheck.register();return;}
        if(Boolean.getBoolean("magiccodex.questCheck")){school.magiccodex.client.QuestVisualCheck.register();return;}
        if(Boolean.getBoolean("taming.labelSmoke")){net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(c->{System.out.println("TAMING_LABEL_MIXIN_STARTUP_OK");c.scheduleStop();});return;}
        if (Boolean.getBoolean("taming.visual")) {school.magiccodex.client.TamingLiveCheck.register();return;}
        if (Boolean.getBoolean("shiny.visual")) {school.magiccodex.client.ShinyLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.reconfigurationCheck")) {school.magiccodex.client.ReconfigurationLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.enhancementHoverCheck")) {school.magiccodex.client.EnhancementHoverCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.enhancementCheck")) {school.magiccodex.client.EnhancementLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.appraisalCheck")) {school.magiccodex.client.AppraisalLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.tooltipCheck")) {school.magiccodex.client.TooltipLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.hasteCheck")) {school.magiccodex.client.HasteLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.equipmentCheck")) {school.magiccodex.client.EquipmentLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.schoolCheck")) {school.magiccodex.client.SchoolLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.petLiveCheck")) {school.magiccodex.client.PetLiveCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.petCheck")) {PetVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.temperatureEdgeCheck")) {TemperatureEdgeVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.temperatureCheck")) {TemperatureVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.seasonCheck")) {SeasonVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.ascensionCheck")) {AscensionVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.detailCheck")) {DetailVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.socialCheck")) {SocialVisualCheck.register();return;}
        if (Boolean.getBoolean("magiccodex.statsCheck")) {
            StatsVisualCheck.register();return;
        }
        if (Boolean.getBoolean("magiccodex.audio200Check")) {
            AllSpellAudioCheck.register();return;
        }
        if (Boolean.getBoolean("magiccodex.castVfxCheck")) {
            CastVfxVisualCheck.register();return;
        }
        if (Boolean.getBoolean("magiccodex.allVfxCheck")) {
            AllVfxVisualCheck.register();return;
        }
        if (Boolean.getBoolean("magiccodex.vfxCheck")) {
            VfxVisualCheck.register();
            return;
        }
        if (Boolean.getBoolean("magiccodex.circleCheck")) {
            CircleVisualCheck.register();
            return;
        }
        if (Boolean.getBoolean("magiccodex.menuCheck")) {
            TopMenuVisualCheck.register();
            return;
        }
        if (Boolean.getBoolean("magiccodex.hudCheck")) {
            PlayerHudVisualCheck.register();
            return;
        }
        if (Boolean.getBoolean("magiccodex.manaCheck")) {
            ManaVisualCheck.register();
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getOverlay() != null) return;
            if (phase == 0 && client.currentScreen instanceof TitleScreen) {
                client.options.getGuiScale().setValue(2);
                client.onResolutionChanged();
                client.setScreen(new CodexScreen(school.magiccodex.client.CodexData.previewSpells()));
                // Reproduce a second mod registering the same I key after our key binding.
                var conflict=new net.minecraft.client.option.KeyBinding("key.codex_test.conflict",GLFW.GLFW_KEY_I,"category.codex_test");
                net.minecraft.client.option.KeyBinding.onKeyPressed(net.minecraft.client.util.InputUtil.fromKeyCode(GLFW.GLFW_KEY_I,0));
                check(conflict.wasPressed(),"Conflicting vanilla binding receives I");
                check(!net.minecraft.client.option.KeyBinding.byId("key.magiccodex.open").wasPressed(),"Original queue misses I when another binding owns it");
                client.keyboard.onKey(client.getWindow().getHandle(),GLFW.GLFW_KEY_I,0,GLFW.GLFW_PRESS,0);
                check(!(client.currentScreen instanceof CodexScreen),"Raw I event still closes codex despite binding collision");
                client.keyboard.onKey(client.getWindow().getHandle(),GLFW.GLFW_KEY_I,0,GLFW.GLFW_RELEASE,0);
                System.out.println("CODEX_SHORTCUT_CONFLICT_REPRODUCED: vanilla queue collision confirmed; raw keyboard mixin loaded; I closes codex");
                client.setScreen(new CodexScreen(school.magiccodex.client.CodexData.previewSpells()));
                click(client, CodexHitboxes.card(0));
                check(screen(client).state().selected().id().equals("magical_flame"), "First card detail");
                phase = 1;
            }
            if (!(client.currentScreen instanceof CodexScreen || client.currentScreen instanceof KeySettingsScreen || client.currentScreen instanceof CastingHudCheckScreen) || ++ticks < (phase == 12 ? 60 : phase == 11 ? 8 : phase == 8 ? 10 : 40)) return;
            ticks = 0;
            try {
                Path output = Path.of("visual-check");
                Files.createDirectories(output);
                if(client.currentScreen instanceof CodexScreen codex) codex.verifyImageRenderer();
                if(client.currentScreen instanceof KeySettingsScreen keys) keys.verifyImageRenderer();
                String[] names = {"", "fire-details", "dark-category", "dark-discovered", "search-empty", "page-two", "wind-details", "wind-details-large", "yaml-loaded", "keys-empty", "keys-picker", "keys-filled", "keys-small", "keys-discard", "hud-on-small", "hud-off-small", "hud-on-large", "hud-ready-large", "hud-bright-large", "hud-filter-comparison"};
                try (var image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                    image.writeTo(output.resolve("codex-" + names[phase] + ".png"));
                }
                switch (phase) {
                    case 1 -> {
                        client.options.getGuiScale().setValue(3);
                        client.onResolutionChanged();
                        click(client, CodexHitboxes.category(Category.DARK.ordinal()));
                        check(screen(client).state().selected() == null, "Category clears old detail");
                        check(screen(client).state().visible().size() == 3, "Dark category count");
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected().id().equals("shadow_veil"), "Dark card detail at GUI scale 3");
                    }
                    case 2 -> {
                        click(client, CodexHitboxes.filter(1));
                        check(screen(client).state().visible().size() == 1, "Discovery filter");
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected().id().equals("night_echo"), "Filtered card detail");
                    }
                    case 3 -> {
                        click(client, CodexHitboxes.SEARCH);
                        screen(client).keyPressed(GLFW.GLFW_KEY_T, 0, 0);
                        screen(client).charTyped('t', 0);
                        check(screen(client).state().query().equals("t"), "Search captures chat key");
                        screen(client).keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                        screen(client).charTyped('밤', 0);
                        check(screen(client).state().visible().size() == 1, "Korean search");
                        screen(client).charTyped('z', 0);
                        check(screen(client).state().visible().isEmpty(), "Empty search result");
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected() == null, "Empty slot cannot select old card");
                    }
                    case 4 -> {
                        click(client, CodexHitboxes.SEARCH);
                        screen(client).keyPressed(GLFW.GLFW_KEY_END, 0, 0);
                        screen(client).keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                        screen(client).keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                        screen(client).keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                        click(client, CodexHitboxes.category(Category.ALL.ordinal()));
                        click(client, CodexHitboxes.filter(0));
                        click(client, CodexHitboxes.NEXT);
                        check(screen(client).state().page() == 1, "Next page click");
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected().id().equals("root_bond"), "Page two detail");
                    }
                    case 5 -> {
                        var screen = screen(client);
                        var layout = CodexLayout.codexFit(screen.width, screen.height);
                        screen.mouseScrolled(layout.x() + 600 * layout.scale(), layout.y() + 400 * layout.scale(), 0, 1);
                        check(screen.state().page() == 0, "Scroll previous page");
                        click(client, CodexHitboxes.PREVIOUS);
                        check(screen.state().page() == 0, "Disabled previous page");
                        long discovered = screen.state().discoveredCount();
                        click(client, CodexHitboxes.DONATE);
                        check(screen.state().discoveredCount() == discovered, "Donation cannot mutate discovery");
                        client.options.getGuiScale().setValue(2);
                        client.onResolutionChanged();
                        click(client, CodexHitboxes.card(1));
                        check(screen(client).state().selected().id().equals("feather_step"), "Feather illustration for concept comparison");
                    }
                    case 6 -> {
                        GLFW.glfwSetWindowSize(client.getWindow().getHandle(), 1920, 1080);
                        client.options.getGuiScale().setValue(3);
                        client.onResolutionChanged();
                    }
                    case 7 -> {
                        check(client.getWindow().getFramebufferWidth() == 1920, "Large viewport width");
                        check(client.getWindow().getFramebufferHeight() == 1080, "Large viewport height");
                        check(CodexCatalog.spells().size() == 18, "18 default YAML files loaded");
                        client.setScreen(new CodexScreen());
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected() == null, "Loading screen blocks unconfirmed card selection");
                        check(!CodexCatalog.spells().getFirst().permissionKnown(), "Local YAML does not invent permission state");
                        Path testFile = CodexCatalog.DIRECTORY.resolve("visual_check.yml");
                        check(!Files.exists(testFile), "Do not overwrite an existing test config");
                        String yaml = """
                                id: yaml_test
                                name: YAML에서 읽은 불씨
                                description: |-
                                  파일에서 읽은 첫 번째 설명입니다.
                                  두 번째 줄도 표시합니다.
                                category: fire
                                rank: 초급
                                icon: magiccodex:textures/spells/magical_flame.png
                                permission: magic.learned.yaml_test
                                discovery:
                                  description: YAML 조건 문구 확인하기
                                research: 클라이언트 파일에서 읽은 연구 기록.
                                cast:
                                  command: cast yamltest
                                  cooldown-seconds: 12
                                order: -100
                                """;
                        Files.writeString(testFile, yaml);
                        check(CodexCatalog.reload(false) == 1, "YAML hot reload succeeded");
                        check(screen(client).state().total() == 19, "Open screen sees newly added YAML");
                        click(client, CodexHitboxes.card(0));
                        check(screen(client).state().selected() == null, "Added YAML remains behind loading until permission confirmation");
                    }
                    case 8 -> {
                        Path testFile = CodexCatalog.DIRECTORY.resolve("visual_check.yml");
                        try {
                            Files.writeString(testFile, "name: [");
                            check(CodexCatalog.reload(false) == 0, "Invalid YAML reload rejected");
                            check(screen(client).state().total() == 19, "Failed reload preserves current catalog");
                            check(screen(client).state().selected() == null, "Failed reload cannot expose unconfirmed details");
                        } finally {
                            Files.delete(testFile);
                            check(CodexCatalog.reload(false) == 1, "Restore YAML catalog");
                        }
                        check(screen(client).state().total() == 18, "Deleted YAML removed on reload");
                        check(screen(client).state().selected() == null, "Deleted spell clears selection");
                        click(client, CodexHitboxes.CLOSE);
                        check(!(client.currentScreen instanceof CodexScreen), "Close button");
                        Path settings=Path.of("visual-check/keys-test.yml");
                        new SpellKeySettings().save(settings);
                        client.setScreen(new KeySettingsScreen(new CodexScreen(CodexData.previewSpells()),CodexData::previewSpells,false,settings));
                    }
                    case 9 -> {
                        click(client,keyBox(GLFW.GLFW_KEY_1));
                        check(((KeySettingsScreen)client.currentScreen).pickerOpen(),"Keyboard key opens picker");
                    }
                    case 10 -> {
                        for(int i=0;i<6;i++) {
                            if(i>0) client.currentScreen.keyPressed(GLFW.GLFW_KEY_1+i,0,0);
                            click(client,KeySettingsLayout.choice(i));
                        }
                        var keys=(KeySettingsScreen)client.currentScreen;
                        check(keys.bindings().stream().noneMatch(SpellKeySettings.Binding::empty),"Six learned spells registered by key");
                        for(int i=0;i<6;i++) check(keys.bindings().get(i).keyCode()==GLFW.GLFW_KEY_1+i,"Slot order follows registration");
                        keys.keyPressed(GLFW.GLFW_KEY_W,0,0);
                        check(!keys.pickerOpen(),"Reserved key rejected");
                        var before=keys.bindings();
                        keys.keyPressed(GLFW.GLFW_KEY_F,0,0); click(client,KeySettingsLayout.choice(6));
                        check(keys.bindings().equals(before),"Seventh spell rejected");
                        click(client,keyBox(GLFW.GLFW_KEY_R)); click(client,KeySettingsLayout.choice(5));
                        check(keys.bindings().get(5).keyCode()==GLFW.GLFW_KEY_R,"Registered spell moves to clicked key");
                        String first=keys.bindings().get(0).spellId(),second=keys.bindings().get(1).spellId();
                        tap(client,KeySettingsLayout.slot(0)); tap(client,KeySettingsLayout.slot(1));
                        check(keys.bindings().get(0).spellId().equals(second),"Click two slots swaps order");
                        tap(client,KeySettingsLayout.slot(0)); tap(client,KeySettingsLayout.slot(1));
                        check(keys.bindings().get(0).spellId().equals(first),"Swap back");
                        click(client,KeySettingsLayout.SAVE);
                        check(SpellKeySettings.load(Path.of("visual-check/keys-test.yml")).slots().equals(keys.bindings()),"Save round trip");
                    }
                    case 11 -> {
                        GLFW.glfwSetWindowSize(client.getWindow().getHandle(),1280,720);
                        client.options.getGuiScale().setValue(2); client.onResolutionChanged();
                    }
                    case 12 -> {
                        var keys=(KeySettingsScreen)client.currentScreen;
                        keys.close(); check(client.currentScreen instanceof CodexScreen,"Saved settings return to codex");
                        client.setScreen(new KeySettingsScreen(client.currentScreen,CodexData::previewSpells,false,Path.of("visual-check/keys-test.yml")));
                        check(((KeySettingsScreen)client.currentScreen).bindings().get(5).keyCode()==GLFW.GLFW_KEY_R,"Reopen persists key");
                        click(client,KeySettingsLayout.RESET);
                        client.currentScreen.close(); check(client.currentScreen instanceof KeySettingsScreen,"Unsaved reset asks before discard");
                    }
                    case 13 -> {
                        click(client,KeySettingsLayout.DISCARD);
                        check(client.currentScreen instanceof CodexScreen,"Discard returns to codex");
                        check(!SpellKeySettings.load(Path.of("visual-check/keys-test.yml")).get(0).empty(),"Discard preserves file");
                        client.setScreen(new CastingHudCheckScreen());
                    }
                    case 14 -> {
                        var hud=(CastingHudCheckScreen)client.currentScreen;
                        check(hud.commands.size()==2,"HUD fixture casts actual assigned keys 1 and R");
                        hud.press(90,3000); check(!hud.state.enabled(),"Z toggles HUD OFF");
                    }
                    case 15 -> {
                        var hud=(CastingHudCheckScreen)client.currentScreen;
                        hud.press(90,3000); check(hud.state.enabled(),"Z toggles HUD ON");
                        check(hud.state.remaining(hud.state.bindings().getFirst().spellId(),3000)>0,"Toggle preserves cooldown");
                        GLFW.glfwSetWindowSize(client.getWindow().getHandle(),1920,1080);
                        client.options.getGuiScale().setValue(3); client.onResolutionChanged();
                    }
                    case 16 -> ((CastingHudCheckScreen)client.currentScreen).now=100000;
                    case 17 -> ((CastingHudCheckScreen)client.currentScreen).bright=true;
                    case 18 -> {
                        var hud=(CastingHudCheckScreen)client.currentScreen;
                        hud.verifyRenderer(); hud.comparison=true;
                    }
                    case 19 -> {
                        System.out.println("HUD_FILTER_CHECK_OK: GPU trilinear filter and complete mip chains verified; original PNG compared at identical sizes");
                        System.out.println("CODEX_VISUAL_CHECK_OK: catalog and key settings regression; HUD ON/OFF, two cooldowns, assigned R, 1280x720 and 1920x1080; casting mixins loaded");
                        client.scheduleStop();
                    }
                }
                phase++;
            } catch (Exception error) {
                throw new IllegalStateException("Codex visual check failed", error);
            }
        });
    }

    private static CodexScreen screen(MinecraftClient client) { return (CodexScreen) client.currentScreen; }
    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Interaction check failed: " + name);
    }
    private static Rect keyBox(int code) {
        return KeySettingsLayout.KEYS.stream().filter(k->k.code()==code).findFirst().orElseThrow().box();
    }
    /** Press and release without moving: a click on a reorderable slot. */
    private static void tap(MinecraftClient client, Rect box) {
        click(client, box);
        var screen = client.currentScreen; var layout = screen instanceof KeySettingsScreen ? KeySettingsScreen.layoutFor(screen.width, screen.height) : CodexLayout.fit(screen.width, screen.height);
        screen.mouseReleased(layout.x() + box.centerX() * layout.scale(), layout.y() + box.centerY() * layout.scale(), GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void click(MinecraftClient client, Rect box) {
        var screen = client.currentScreen;
        var layout = screen instanceof CodexScreen ? CodexLayout.codexFit(screen.width, screen.height)
                : screen instanceof KeySettingsScreen ? KeySettingsScreen.layoutFor(screen.width, screen.height) : CodexLayout.fit(screen.width, screen.height);
        check(screen.mouseClicked(layout.x() + box.centerX() * layout.scale(),
                layout.y() + box.centerY() * layout.scale(), GLFW.GLFW_MOUSE_BUTTON_LEFT), "Click was handled");
    }
}
