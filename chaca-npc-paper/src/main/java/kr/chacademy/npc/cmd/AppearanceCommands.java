package kr.chacademy.npc.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import kr.chacademi.npcmodel.protocol.NpcModelProtocol;
import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.appearance.AppearanceConfig;
import kr.chacademy.npc.appearance.AppearanceService;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.npc.NpcManager;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /NPC관리 외형 … — NPC 3D 외형 운영 명령 (관리 권한은 허브의 OP 검사 그대로).
 *
 * <pre>
 * 지정 &lt;모델&gt; [크기]              가장 가까운 NPC 하나만 이 모델로 (서버ID:번호 로 저장)
 * 캐릭터 &lt;캐릭터&gt; &lt;모델|없음&gt; [크기] 캐릭터 기본값 (NPC를 다시 만들어도 따라감)
 * 해제                           가장 가까운 NPC의 개별 지정을 지움 → 캐릭터 기본값(없으면 원래 외형)
 * 끄기                           가장 가까운 NPC만 원래 외형으로 고정
 * 크기 &lt;크기&gt;                    가장 가까운 NPC의 지금 모델 크기 변경 (0.25~10)
 * 동작 &lt;대기|대화|인사&gt;           보는 사람에게 동작 테스트 (서버 판정과 무관)
 * 방향 &lt;0~360|나|지금|해제&gt;      가장 가까운 NPC 모델을 그 각도로 고정 (마인크래프트 yaw: 0=남 90=서 180=북 270=동)
 * 정보 · 목록 · 모델등록 &lt;ID&gt; · 모델삭제 &lt;ID&gt; · 통계 · 새로고침
 * </pre>
 */
public final class AppearanceCommands {
    private static final String NONE_KO = "없음";
    private static final List<String> SCALES = List.of("0.8", "0.9", "1.0", "1.1", "1.2");
    private static final List<String> FACINGS = List.of("0", "90", "180", "270", "나", "지금", "해제");

    private final ChacaNpcPlugin plugin;

    public AppearanceCommands(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    private AppearanceService svc() {
        return plugin.appearance();
    }

    public void register() {
        if (!ChacaLink.ready()) {
            plugin.getLogger().warning("[ChacaNPC] 명령어 허브가 없어 NPC 외형 명령을 등록하지 않았어요 (외형 동기화 자체는 동작)");
            return;
        }
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("외형")
                .then(Commands.literal("지정").then(Commands.argument("모델", modelArg(false))
                        .executes(ctx -> assign(ctx, false))
                        .then(Commands.argument("크기", ChacaLink.word(() -> SCALES)).executes(ctx -> assign(ctx, true)))))
                .then(Commands.literal("캐릭터").then(Commands.argument("캐릭터", characterArg())
                        .then(Commands.argument("모델", modelArg(true))
                                .executes(ctx -> character(ctx, false))
                                .then(Commands.argument("크기", ChacaLink.word(() -> SCALES)).executes(ctx -> character(ctx, true))))))
                .then(Commands.literal("해제").executes(this::clear))
                .then(Commands.literal("끄기").executes(this::off))
                .then(Commands.literal("크기").then(Commands.argument("크기", ChacaLink.word(() -> SCALES)).executes(this::scale)))
                .then(Commands.literal("동작")
                        .then(Commands.literal("대기").executes(ctx -> motion(ctx, false, false)))
                        .then(Commands.literal("대화").executes(ctx -> motion(ctx, true, false)))
                        .then(Commands.literal("인사").executes(ctx -> motion(ctx, false, true))))
                .then(Commands.literal("방향").then(Commands.argument("각도", ChacaLink.word(() -> FACINGS)).executes(this::facing)))
                .then(Commands.literal("정보").executes(this::info))
                .then(Commands.literal("목록").executes(this::list))
                .then(Commands.literal("모델등록").then(Commands.argument("모델ID", ChacaLink.word(List::of)).executes(ctx -> registerModel(ctx, true))))
                .then(Commands.literal("모델삭제").then(Commands.argument("모델ID", modelArg(false)).executes(ctx -> registerModel(ctx, false))))
                .then(Commands.literal("통계").executes(ctx -> {
                    ChacaLink.ok(ctx.getSource().getSender(), svc().stats());
                    return 1;
                }))
                .then(Commands.literal("새로고침").executes(ctx -> {
                    svc().reload();
                    ChacaLink.ok(ctx.getSource().getSender(), "npc-models.yml 을 다시 읽고 보는 사람에게 다시 보냈어요");
                    return 1;
                }));
        ChacaLink.branch(plugin, ChacaLink.NPC관리, root, "NPC 3D 외형 지정·해제·크기·방향·동작 테스트", "외형 정보");
        ChacaLink.reload(plugin, "NPC외형", "NPC 3D 외형 설정(npc-models.yml)", () -> svc().reload());
        ChacaLink.status(plugin, "NPC 외형", () -> svc().stats());
    }

    // ================================================================ 실행

    private int assign(CommandContext<CommandSourceStack> ctx, boolean withScale) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        String model = model(ctx, false);
        float scale = withScale ? parseScale(ctx) : currentScaleOr(npc, 1f);
        svc().config().setNpc(npc.getId(), new AppearanceConfig.Spec(model, scale));
        save(p);
        svc().refresh(npc);
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 외형 → " + model + " (크기 " + scale + ", " + svc().config().serverId() + ":" + npc.getId() + " 로 저장)");
        return 1;
    }

    private int character(CommandContext<CommandSourceStack> ctx, boolean withScale) throws CommandSyntaxException {
        CharacterSheet c = characterOf(ctx);
        String model = model(ctx, true);
        if (model == null) {
            svc().config().setCharacter(c.id(), null);
        } else {
            svc().config().setCharacter(c.id(), new AppearanceConfig.Spec(model, withScale ? parseScale(ctx) : 1f));
        }
        save(ctx.getSource().getSender());
        Integer npcId = plugin.npcs().npcIdFor(c.id());
        if (npcId != null) {
            NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null) {
                svc().refresh(npc);
            }
        }
        ChacaLink.ok(ctx.getSource().getSender(), c.name() + " 캐릭터 기본 외형 → " + (model == null ? "원래 외형" : model));
        return 1;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        if (svc().config().npc(npc.getId()) == null) {
            throw ChacaLink.error("이 NPC는 개별 지정이 없어요 (캐릭터 기본값은 '외형 캐릭터 … 없음' 으로)");
        }
        svc().config().setNpc(npc.getId(), null);
        save(p);
        svc().refresh(npc);
        AppearanceConfig.Spec now = svc().resolve(npc);
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 개별 지정을 지웠어요 → " + (now == null ? "원래 외형" : "캐릭터 기본값 " + now.model()));
        return 1;
    }

    private int off(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        svc().config().setNpc(npc.getId(), new AppearanceConfig.Spec(AppearanceConfig.NONE, 1f));
        save(p);
        svc().refresh(npc);
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 는 원래 외형으로 고정했어요 (되돌리기: 외형 해제)");
        return 1;
    }

    private int scale(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        AppearanceConfig.Spec cur = svc().resolve(npc);
        if (cur == null) {
            throw ChacaLink.error("이 NPC는 3D 외형이 없어요 (먼저 외형 지정)");
        }
        float s = parseScale(ctx);
        svc().config().setNpc(npc.getId(), new AppearanceConfig.Spec(cur.model(), s));
        save(p);
        svc().refresh(npc);
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 크기 → " + s);
        return 1;
    }

    /** 모델 방향 고정: 숫자(yaw) · 나(나를 바라봄) · 지금(NPC의 지금 방향) · 해제(몸 방향을 따라감). */
    private int facing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        String in = ctx.getArgument("각도", String.class).trim();
        Float yaw;
        switch (in) {
            case "해제" -> yaw = null;
            case "나" -> {
                org.bukkit.Location n = npc.getEntity().getLocation(), me = p.getLocation();
                double dx = me.getX() - n.getX(), dz = me.getZ() - n.getZ();
                if (dx * dx + dz * dz < 1e-4) {
                    throw ChacaLink.error("NPC와 같은 자리에 서 있어요 — 조금 떨어져서 다시");
                }
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); // 마인크래프트 yaw: +Z(남)=0, 시계 방향
            }
            case "지금" -> yaw = npc.getEntity().getLocation().getYaw();
            default -> {
                try {
                    yaw = Float.parseFloat(in);
                } catch (NumberFormatException e) {
                    throw ChacaLink.error("방향은 0~360 숫자, 나, 지금, 해제 중 하나예요 (0=남 90=서 180=북 270=동)");
                }
                if (!Float.isFinite(yaw)) {
                    throw ChacaLink.error("방향은 0~360 숫자예요");
                }
            }
        }
        svc().config().setFacing(npc.getId(), yaw);
        save(p);
        svc().refresh(npc);
        Float now = svc().config().facing(npc.getId());
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 모델 방향 → " + (now == null ? "NPC 몸 방향 따라감" : now + "° 고정 (" + compass(now) + ")"));
        return 1;
    }

    private static String compass(float yaw) {
        String[] d = {"남", "남서", "서", "북서", "북", "북동", "동", "남동"};
        return d[Math.round(yaw / 45f) % 8];
    }

    private int motion(CommandContext<CommandSourceStack> ctx, boolean talk, boolean greet) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        if (svc().resolve(npc) == null) {
            throw ChacaLink.error("이 NPC는 3D 외형이 없어요");
        }
        if (!svc().isCapable(p)) {
            ChacaLink.notify(p, "warning", "npcmodel-nomod", "NPC 모델 모드가 연결되지 않았어요", "보내기는 했지만 내 화면에서는 원래 외형으로 보여요", 5000);
        }
        svc().testState(npc, talk, greet);
        ChacaLink.ok(p, "NPC #" + npc.getId() + " 동작 테스트: " + (greet ? "인사" : talk ? "대화(끝내려면 대기)" : "대기")
                + " — 클립이 없으면 대기 동작으로 보여요");
        return 1;
    }

    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        NPC npc = near(p);
        Object charId = npc.data().has(NpcManager.DATA_KEY) ? npc.data().get(NpcManager.DATA_KEY) : null;
        AppearanceConfig.Spec own = svc().config().npc(npc.getId());
        AppearanceConfig.Spec chr = svc().config().character(charId == null ? null : charId.toString());
        AppearanceConfig.Spec now = svc().resolve(npc);
        Entity ent = npc.isSpawned() ? npc.getEntity() : null;
        int viewers = 0, modViewers = 0;
        if (ent != null) {
            for (Player v : ent.getTrackedBy()) {
                viewers++;
                if (svc().isCapable(v)) {
                    modViewers++;
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("NPC #" + npc.getId() + " (" + svc().config().serverId() + ":" + npc.getId() + ") · 캐릭터 " + (charId == null ? "연결 없음" : charId));
        out.add("지금 외형: " + (now == null ? "원래 외형" : now.model() + " ×" + now.scale()));
        out.add("개별 지정: " + (own == null ? "없음" : own.model() + " ×" + own.scale()) + " · 캐릭터 기본값: " + (chr == null ? "없음" : chr.model() + " ×" + chr.scale()));
        Float face = svc().config().facing(npc.getId());
        out.add("모델 방향: " + (face == null ? "NPC 몸 방향 따라감" : face + "° 고정 (" + compass(face) + ")"));
        out.add("보는 사람 " + viewers + "명 (모드 연결 " + modViewers + "명) · 내 모드 연결: " + (svc().isCapable(p) ? "됨" : "안 됨"));
        if (now != null && !svc().config().knowsModel(now.model())) {
            out.add("주의: '" + now.model() + "' 는 models 목록에 없어요 (클라이언트 models.json 과 맞는지 확인)");
        }
        ChacaLink.lines(p, out);
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        AppearanceConfig cfg = svc().config();
        List<String> out = new ArrayList<>();
        out.add("서버 " + cfg.serverId() + " · 모델 " + cfg.models() + " · 3D 외형 " + (svc().enabled() ? "켜짐" : "꺼짐"));
        for (Map.Entry<String, AppearanceConfig.Spec> e : cfg.characterSpecs().entrySet()) {
            out.add("캐릭터 " + e.getKey() + " → " + e.getValue().model() + " ×" + e.getValue().scale());
        }
        for (Map.Entry<String, AppearanceConfig.Spec> e : cfg.npcSpecs().entrySet()) {
            out.add("NPC " + e.getKey() + " → " + (AppearanceConfig.NONE.equals(e.getValue().model()) ? "원래 외형" : e.getValue().model() + " ×" + e.getValue().scale()));
        }
        for (Map.Entry<String, Float> e : cfg.facingSpecs().entrySet()) {
            out.add("방향 " + e.getKey() + " → " + e.getValue() + "°");
        }
        ChacaLink.lines(ctx.getSource().getSender(), out);
        return 1;
    }

    private int registerModel(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        String id = ctx.getArgument("모델ID", String.class).trim();
        if (!NpcModelProtocol.MODEL_ID.matcher(id).matches() || id.equals(AppearanceConfig.NONE)) {
            throw ChacaLink.error("모델 ID는 영소문자·숫자·_- 1~32자예요 (클라이언트 models.json 의 이름과 같게)");
        }
        if (add) {
            svc().config().addModel(id);
        } else {
            if (!svc().config().knowsModel(id)) {
                throw ChacaLink.error("'" + id + "' 는 목록에 없어요");
            }
            svc().config().removeModel(id);
        }
        save(ctx.getSource().getSender());
        ChacaLink.ok(ctx.getSource().getSender(), "모델 목록 " + (add ? "추가" : "삭제") + ": " + id + " (지정된 NPC 외형은 그대로)");
        return 1;
    }

    // ================================================================ 칸 해석

    private static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) {
            return p;
        }
        throw ChacaLink.error("게임 안에서만 쓸 수 있어요");
    }

    private NPC near(Player p) throws CommandSyntaxException {
        NPC npc = plugin.npcs().nearestAny(p, 5);
        if (npc == null) {
            throw ChacaLink.error("5칸 안에 Citizens NPC가 없어요");
        }
        if (!npc.isSpawned() || npc.getEntity().getType() != org.bukkit.entity.EntityType.PLAYER) {
            throw ChacaLink.error("플레이어 모양 NPC만 3D 외형을 쓸 수 있어요");
        }
        return npc;
    }

    private com.mojang.brigadier.arguments.ArgumentType<String> modelArg(boolean allowNone) {
        return ChacaLink.word(() -> {
            Set<String> s = new LinkedHashSet<>(svc().config().models());
            if (allowNone) {
                s.add(NONE_KO);
            }
            return s;
        });
    }

    /** 모델 칸. allowNone 이면 "없음" → null. 목록에 없는 ID는 오타로 보고 거부. */
    private String model(CommandContext<CommandSourceStack> ctx, boolean allowNone) throws CommandSyntaxException {
        String in = ctx.getArgument("모델", String.class).trim();
        if (allowNone && (in.equals(NONE_KO) || in.equals(AppearanceConfig.NONE))) {
            return null;
        }
        if (!svc().config().knowsModel(in)) {
            throw ChacaLink.error("'" + in + "' 모델이 없어요. 있는 모델: " + svc().config().models()
                    + " (새 모델은 '외형 모델등록 <ID>' 후 클라이언트 models.json 에도 추가)");
        }
        return in;
    }

    private static float parseScale(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String in = ctx.getArgument("크기", String.class).trim();
        float s;
        try {
            s = Float.parseFloat(in);
        } catch (NumberFormatException e) {
            throw ChacaLink.error("크기는 숫자예요 (예: 1.0)");
        }
        if (!(s >= NpcModelProtocol.MIN_SCALE && s <= NpcModelProtocol.MAX_SCALE)) {
            throw ChacaLink.error("크기는 " + NpcModelProtocol.MIN_SCALE + " ~ " + NpcModelProtocol.MAX_SCALE + " 사이예요");
        }
        return Math.round(s * 100) / 100f;
    }

    private float currentScaleOr(NPC npc, float def) {
        AppearanceConfig.Spec s = svc().resolve(npc);
        return s == null ? def : s.scale();
    }

    private static String label(CharacterSheet c) {
        String n = c.name() == null ? "" : c.name().replaceAll("\\s+", "");
        return n.isEmpty() ? c.id() : n;
    }

    private com.mojang.brigadier.arguments.ArgumentType<String> characterArg() {
        return ChacaLink.word(() -> {
            Set<String> out = new LinkedHashSet<>();
            for (CharacterSheet c : plugin.characters().all()) {
                out.add(label(c));
            }
            return out;
        });
    }

    private CharacterSheet characterOf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String in = ctx.getArgument("캐릭터", String.class);
        CharacterSheet c = plugin.characters().get(in);
        if (c == null) {
            for (CharacterSheet x : plugin.characters().all()) {
                if (label(x).equals(in)) {
                    c = x;
                    break;
                }
            }
        }
        if (c == null) {
            throw ChacaLink.error("'" + in + "' 캐릭터가 없어요");
        }
        return c;
    }

    private void save(org.bukkit.command.CommandSender to) throws CommandSyntaxException {
        try {
            svc().config().save();
        } catch (java.io.IOException e) {
            ChacaLink.fail(to, "npc-models.yml 저장에 실패했어요 (바뀐 외형은 이번 실행 동안만 유지)", e);
        }
    }
}
