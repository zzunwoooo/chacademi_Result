package school.magiccodex.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

public final class MagicCodexClient implements ClientModInitializer {
    private static boolean visible;
    private static String pendingChat;
    private static KeyBinding openKey;
    private static final CodexShortcut SHORTCUT = new CodexShortcut();
    static KeyBinding binding() { return openKey; }
    static boolean matchesOpenKey(int key, int scan) { return openKey != null && openKey.matchesKey(key, scan); }
    public static boolean handleOpenShortcut(MinecraftClient client, int key, int scan, int action) {
        boolean ready = client.world != null && client.player != null && client.currentScreen == null
                && client.getOverlay() == null && client.isWindowFocused();
        var result = SHORTCUT.handle(key, action, matchesOpenKey(key, scan), ready);
        if (result == CodexShortcut.Result.OPEN) visible = true;
        return result != CodexShortcut.Result.PASS;
    }

    @Override
    public void onInitializeClient() {
        UiResources.initialize();
        UiKeys.initialize();
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.magiccodex.open", GLFW.GLFW_KEY_K, "category.magiccodex"));
        CodexCatalog.initialize();
        PermissionClient.initialize();
        NameplateClient.initialize();
        WalletClient.initialize();
        TemperatureClient.initialize();
        SeasonClient.initialize();
        ManaClient.initialize();
        StatsClient.initialize();
        AscensionClient.initialize();
        AppraisalClient.initialize();EnhancementClient.initialize();ReconfigurationClient.initialize();
        RemoteStatsClient.initialize();
        DiscoveryClient.initialize();
        SocialClient.initialize();
        SchoolClient.initialize();
        NicknameClient.initialize();MailboxClient.initialize();ShopClient.initialize();ShopAdminClient.initialize();LootBoxClient.initialize();
        TitleClient.initialize();
        QuestClient.initialize();QuestAdminClient.initialize();JournalClient.initialize();
        DialogueClient.initialize();
        NpcTalkClient.initialize();PortraitClient.initialize();
        EquipmentClient.initialize();
        ArtifactClient.initialize();
        SpyglassZoom.initialize();
        ArtifactFxClient.initialize();
        AlchemyClient.initialize();
        AlchemyAdminClient.initialize();
        FishingClient.initialize();
        FishShopClient.initialize();
        BookClient.initialize();
        MegaphoneClient.initialize();
        TeleportPickClient.initialize();   // 텔포권 유저 고르기 창 (2026-10-26)
        TarotClient.initialize();   // 마법 힌트권 「운명의 타로」 창 (§41)
        DrawBoardClient.initialize();   // 운영자 「행운의 뽑기판」 100칸 (§44)
        BookMarks.initialize();
        TravelClient.initialize();
        ChunkClaimClient.initialize();   // 야생 청크 점유 (메모 #88)
        HouseBoardClient.initialize();   // 기숙사 점수 홀로그램 (메모 #106)
        MileageClient.initialize();
        FishTankClient.initialize();
        BagPagesClient.initialize();
        PetClient.initialize();
        PetCompanions.initialize();
        TamingClient.initialize();
        ShinyClient.initialize();
        CastingClient.initialize();
        AimClient.initialize();
        ElementMarkClient.initialize();
        StatusClient.initialize();
        TelegraphClient.initialize();
        TrainingDummyClient.initialize(); // 마법 연습장 허수아비 머리 위 누적·떠오르는 피해 숫자 (#107)
        TargetHudClient.initialize();
        PlayerHudClient.initialize();
        KyudichiClient.initialize();
        KyudichiQueueClient.initialize();
        KyudichiPreview.initialize();
        PreviewClient.initialize();
        HudChatClicks.initialize();
        PartyHudClient.initialize();
        PartyUiClient.initialize();
        HousingClient.initialize();
        ScreenVfxClient.initialize();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("화면")
                    .executes(context -> setVisible(!visible))
                    .then(ClientCommandManager.literal("켜기").executes(context -> setVisible(true)))
                    .then(ClientCommandManager.literal("끄기").executes(context -> setVisible(false)))
                    .then(ClientCommandManager.literal("새로고침").executes(context -> CodexCatalog.reload(true))));
        });

        // A command runs inside ChatScreen, which closes itself after sending. Open on the
        // next tick so ChatScreen cannot immediately close the new codex screen again.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Opening uses the raw callback above: another mod can own vanilla's key lookup.
            while (openKey.wasPressed()) { /* Drain only; never toggle twice for one press. */ }
            if (!client.isWindowFocused()) SHORTCUT.reset();
            CastingClient.tick(client);
            PermissionClient.tick(client);
            WalletClient.tick(client);
            TemperatureClient.tick(client);
            SeasonClient.tick(client);
            ManaClient.tick(client);
            if (client.world == null || client.player == null) {
                visible = false;
                pendingChat = null;
                SHORTCUT.reset();
                return;
            }
            if (pendingChat != null) {
                String text = pendingChat;
                pendingChat = null;
                if (visible && client.currentScreen instanceof CodexScreen) {
                    client.setScreen(new ChatScreen(text));
                    return;
                }
            }
            if (visible && client.currentScreen == null) {
                client.setScreen(new CodexScreen());
            } else if (!visible && client.currentScreen instanceof CodexScreen) {
                client.setScreen(null);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { dismiss(); SHORTCUT.reset(); });
    }

    private static int setVisible(boolean enabled) {
        visible = enabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(enabled
                    ? "마법 도감 열기 · ESC 닫기 · / 키로 명령어 입력"
                    : "마법 도감을 닫았습니다."), false);
        }
        return 1;
    }

    static void dismiss() {
        visible = false;
        pendingChat = null;
    }

    static void openFromMenu() {
        visible=true;
        pendingChat=null;
        MinecraftClient.getInstance().setScreen(new CodexScreen());
    }

    static void requestChat(String initialText) {
        // Defer until after GLFW's character event so '/' is not inserted twice.
        pendingChat = initialText;
    }
}

