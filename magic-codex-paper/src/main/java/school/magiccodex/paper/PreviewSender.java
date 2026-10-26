package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.PreviewProtocol;

/**
 * /테스트 아래 클라이언트 미리보기 (2026-10-20 명령어 정리). 서버는 {@link PreviewProtocol} 채널로 "종류 값…" 한 줄만 보내고,
 * MagicCodex 모드가 받아서 기존 미리보기 화면을 띄운다. 이 모듈의 다른 /테스트 갈래(교화몹·의뢰 이벤트)도 같은 이름의 갈래가
 * 같은 플러그인 안에서 덮어써지지 않도록 여기서 한 번에 붙인다.
 */
final class PreviewSender {
    private final MagicCodexBridge plugin;
    private final ManaBridge mana;

    private static final LinkedHashMap<String, String> KYUDICHI = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> TARGET_HUD = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> SEASONS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> NOTICES = new LinkedHashMap<>();
    static {
        String[][] k = {{"로비", "lobby"}, {"결투로비", "duel_lobby"}, {"신청", "apply"}, {"결투", "duel"}, {"준비", "prepare"}, {"경기", "match"},
                {"결과", "result"}, {"수락", "accept"}, {"매칭", "matching"}, {"대시보드", "dashboard"}, {"끄기", "off"}};
        for (var e : k) KYUDICHI.put(e[0], e[1]);
        String[][] t = {{"일반", "normal"}, {"변색종", "shiny"}, {"플레이어", "player"}, {"보스", "boss"}, {"끄기", "off"}};
        for (var e : t) TARGET_HUD.put(e[0], e[1]);
        String[][] s = {{"봄", "spring"}, {"여름", "summer"}, {"가을", "autumn"}, {"겨울", "winter"}};
        for (var e : s) SEASONS.put(e[0], e[1]);
        String[][] n = {{"정보", "system"}, {"경고", "warning"}, {"실패", "error"}, {"보상", "shop"}};
        for (var e : n) NOTICES.put(e[0], e[1]);
    }

    PreviewSender(MagicCodexBridge plugin, ManaBridge mana) {
        this.plugin = plugin;
        this.mana = mana;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PreviewProtocol.CHANNEL);
        if (!CodexCommands.ready(plugin, "/테스트 미리보기")) return;
        register();
    }

    /** 명령을 친 플레이어에게 미리보기를 보낸다. 모드가 채널을 열지 않았으면 유저 실패. */
    void send(Player p, String kind, String... values) throws CommandSyntaxException {
        if (!p.getListeningPluginChannels().contains(PreviewProtocol.CHANNEL))
            throw ChacaApi.error("미리보기를 받을 수 있는 MagicCodex 모드가 필요해요");
        p.sendPluginMessage(plugin, PreviewProtocol.CHANNEL, PreviewProtocol.encode(kind, values));
    }

    private int preview(CommandContext<CommandSourceStack> ctx, String kind, String... values) throws CommandSyntaxException {
        Player p = CodexCommands.self(ctx);
        send(p, kind, values);
        ChacaApi.ok(p, "미리보기를 띄웠어요");
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> choices(String name, Map<String, String> map, String kind) {
        var node = Commands.literal(name);
        for (var e : map.entrySet()) node.then(Commands.literal(e.getKey()).executes(ctx -> preview(ctx, kind, e.getValue())));
        return node;
    }

    private int soundPreview(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String event) throws CommandSyntaxException {
        if (!(ctx.getSource().getSender() instanceof org.bukkit.entity.Player p)) throw ChacaApi.error("게임 안에서 써 주세요");
        String id = spellId(ctx.getArgument("효과음", String.class));
        var sounds = plugin.spellSounds();
        if (sounds == null || sounds.events(id).isEmpty()) throw ChacaApi.error(id + " 마법의 효과음이 spell-sounds.yml 에 없어요");
        if (event != null) { plugin.spellSound(null, id, event, p.getLocation()); return 1; }
        int at = 0;
        for (String e : List.of("cast", "hit", "end")) {
            if (!sounds.events(id).contains(e)) continue;
            int delay = at; org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.spellSound(null, id, e, p.getLocation()); }, Math.max(1, delay));
            at += 22;
        }
        ChacaApi.ok(ctx.getSource().getSender(), id + " 효과음: " + String.join(" → ", sounds.events(id)));
        return 1;
    }

    private Collection<String> spellChoices() {
        var out = new TreeSet<String>();
        try {
            for (var e : mana.registeredSpellCatalog().entrySet()) {
                out.add(e.getKey());
                String name = e.getValue().getOrDefault("name", "").replace(" ", "");
                if (!name.isEmpty()) out.add(name);
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    private String spellId(String input) throws CommandSyntaxException {
        var catalog = mana.registeredSpellCatalog();
        if (catalog.containsKey(input)) return input;
        String key = input.replace(" ", "");
        for (var e : catalog.entrySet()) if (e.getValue().getOrDefault("name", "").replace(" ", "").equals(key)) return e.getKey();
        throw ChacaApi.error(input + " 마법을 찾을 수 없어요");
    }

    private void register() {
        // /테스트 큐디치 화면 <…>
        ChacaApi.branch(plugin, ChacaApi.테스트, Commands.literal("큐디치").then(choices("화면", KYUDICHI, "kyudichi")),
                "큐디치·결투 화면 미리보기", "큐디치 화면 로비");

        // /테스트 마법 <효과음|발견화면|승급화면>
        var magic = Commands.literal("마법")
                // #47: 서버 효과음(마크 기본 사운드 층)을 내 자리에서 들어 보기. 사건을 안 적으면 시전 → 1초 뒤 적중 → 2초 뒤 끝.
                .then(Commands.literal("효과음").then(Commands.argument("효과음", ChacaApi.word(this::spellChoices)).executes(ctx -> soundPreview(ctx, null))
                        .then(Commands.argument("사건", ChacaApi.word(() -> List.of("cast", "hit", "end"))).executes(ctx -> soundPreview(ctx, ctx.getArgument("사건", String.class))))))
                .then(Commands.literal("발견화면").executes(ctx -> preview(ctx, "discovery"))
                        .then(Commands.argument("마법", ChacaApi.word(this::spellChoices))
                                .executes(ctx -> preview(ctx, "discovery", spellId(ctx.getArgument("마법", String.class))))))
                .then(Commands.literal("승급화면").executes(ctx -> preview(ctx, "ascension"))
                        .then(Commands.argument("서클", IntegerArgumentType.integer(2, 9))
                                .executes(ctx -> preview(ctx, "ascension", Integer.toString(IntegerArgumentType.getInteger(ctx, "서클"))))));
        ChacaApi.branch(plugin, ChacaApi.테스트, magic, "마법 효과음·발견·승급 화면 미리보기", "마법 발견화면");

        // /테스트 몹 <체력바|교화몹>
        var mob = Commands.literal("몹")
                .then(choices("체력바", TARGET_HUD, "targethud"))
                .then(Commands.literal("교화몹").then(Commands.argument("프로필", ChacaApi.word(() -> {
                    var t = plugin.tamingBridge(); return t == null ? List.of() : t.profileIds();
                })).executes(ctx -> {
                    Player p = CodexCommands.self(ctx);
                    var t = plugin.tamingBridge();
                    if (t == null) throw ChacaApi.error("교화 기능이 준비되지 않았어요");
                    String name = t.spawnExample(p, ctx.getArgument("프로필", String.class));
                    ChacaApi.ok(p, "교화 예시 몹을 소환했어요: " + name);
                    return Command.SINGLE_SUCCESS;
                })));
        ChacaApi.branch(plugin, ChacaApi.테스트, mob, "몹 체력바 미리보기·교화 예시 몹", "몹 체력바 일반");

        // /테스트 화면 <계절|온도|알림>
        var screen = Commands.literal("화면")
                .then(choices("계절", SEASONS, "season"))
                .then(Commands.literal("온도").then(Commands.argument("값", ChacaApi.word(() -> List.of("자동", "-10", "0", "20", "35")))
                        .executes(ctx -> {
                            String v = ctx.getArgument("값", String.class);
                            if (v.equals("자동")) return preview(ctx, "temperature", "auto");
                            float t;
                            try { t = Float.parseFloat(v); } catch (NumberFormatException e) { throw ChacaApi.error("숫자 또는 '자동'을 입력해 주세요"); }
                            if (!Float.isFinite(t) || t < -100 || t > 100) throw ChacaApi.error("온도는 -100~100 사이로 입력해 주세요");
                            return preview(ctx, "temperature", t == Math.rint(t) ? Integer.toString((int) t) : Float.toString(t));
                        })))
                .then(choices("알림", NOTICES, "notice"));
        ChacaApi.branch(plugin, ChacaApi.테스트, screen, "계절·온도·알림 화면 미리보기", "화면 계절 겨울");

        // /테스트 의뢰 이벤트 <이벤트> [수량] [닉네임]
        var quest = Commands.literal("의뢰").then(Commands.literal("이벤트").then(Commands.argument("이벤트", ChacaApi.word(() -> {
            var q = plugin.questBridge(); return q == null ? List.of() : q.eventIds();
        })).executes(ctx -> questEvent(ctx, 1, null))
                .then(Commands.argument("수량", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> questEvent(ctx, IntegerArgumentType.getInteger(ctx, "수량"), null))
                        .then(Commands.argument("닉네임", ChacaApi.player())
                                .executes(ctx -> questEvent(ctx, IntegerArgumentType.getInteger(ctx, "수량"), ChacaApi.resolveOnline(ctx, "닉네임")))))));
        ChacaApi.branch(plugin, ChacaApi.테스트, quest, "의뢰 이벤트 진행 올리기", "의뢰 이벤트 ");
    }

    private int questEvent(CommandContext<CommandSourceStack> ctx, int amount, Player target) throws CommandSyntaxException {
        Player p = target != null ? target : CodexCommands.self(ctx);
        var q = plugin.questBridge();
        if (q == null) throw ChacaApi.error("의뢰 기능이 준비되지 않았어요");
        String id = ctx.getArgument("이벤트", String.class);
        q.testEvent(p, id, amount);
        ChacaApi.ok(ctx.getSource().getSender(), ChacaApi.name(p.getUniqueId()) + " 님의 의뢰 이벤트 " + id + " +" + amount);
        return Command.SINGLE_SUCCESS;
    }
}
