package kr.chacademy.cutscene.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 서버 플러그인과 주고받는 패킷. 바이트 형식은 플러그인의 {@code StoryCodec} 과 같아야 한다.
 * <ul>
 *   <li>문자열: VarInt(UTF-8 바이트 길이) + UTF-8 바이트</li>
 *   <li>정수: VarInt</li>
 *   <li>참/거짓: 1바이트 (0 또는 1)</li>
 * </ul>
 * 프로토콜 버전: 이 모드는 {@code chacademy:story_v<PROTOCOL>} 채널을 듣는다. 서버는 그 채널 이름으로 모드 버전을 알고,
 * 낮은 버전이면 아무것도 보내지 않고 "모드를 업데이트해 주세요" 라고 알린다 (자세한 것은 FORMAT.md "서버와 주고받는 신호").
 * <p>문자열 길이: 클라 → 서버는 {@value #MAX_C2S_STRING}자까지 (서버가 그보다 길면 패킷을 버린다. 여기서 미리 자른다),
 * 서버 → 클라는 마인크래프트 기본 한도 (32767자).
 */
public final class CutscenePackets {
    public static final String NAMESPACE = "chacademy";

    /** 이 모드의 프로토콜 버전 (플러그인 StoryCodec.PROTOCOL 과 같아야 한다). */
    public static final int PROTOCOL = 2;
    /** 클라가 보내는 문자열 한 개의 최대 글자 수 (플러그인 StoryCodec.MAX_STRING). */
    public static final int MAX_C2S_STRING = 256;
    private static final StreamCodec<io.netty.buffer.ByteBuf, String> SHORT_STRING = ByteBufCodecs.stringUtf8(MAX_C2S_STRING);

    public static final int KIND_CUTSCENE = 0;
    public static final int KIND_DIALOGUE = 1;
    /** {@link StoryFailC2S} 의 이유. 파일이 없음 / 읽을 수 없음(형식·이름 규칙) / 불러오다 시간 초과 / MagicCodex 대화창을 쓸 수 없음. */
    public static final String FAIL_MISSING = "missing", FAIL_BROKEN = "broken", FAIL_TIMEOUT = "timeout", FAIL_MAGICCODEX = "magiccodex";

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > MAX_C2S_STRING ? s.substring(0, MAX_C2S_STRING) : s;
    }

    /**
     * 서버가 보여 주라고 한 컷신·대화를 보여 줄 수 없을 때 서버에 알린다 (파일 없음·깨짐, MagicCodex 없음 등).
     * 서버는 끝난 것으로 치지 않고 (on-finish / end 명령 실행 안 함) 콘솔에 경고를 남기며, 파일을 맞추고 다시 접속하면 이어진다.
     * 컷신과 대화가 같이 쓰는 단 하나의 창구다. 클라이언트 스레드에서 부른다.
     *
     * @param kind   {@link #KIND_CUTSCENE} 또는 {@link #KIND_DIALOGUE}
     * @param reason {@link #FAIL_MISSING} 등 짧은 영문 이유
     * @return 서버로 보냈으면 true (서버 플러그인이 예전 버전이면 false — 그때는 아무것도 보내지 않는다)
     */
    public static boolean sendContentMissing(int kind, String id, String reason) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(StoryFailC2S.TYPE)) return false;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new StoryFailC2S(kind, id, reason));
        return true;
    }

    /** 건너뛰기 규칙: 본 적 있으면 건너뛰기 가능. */
    public static final int MODE_AUTO = 0;
    /** 항상 건너뛰기 가능. */
    public static final int MODE_SKIP = 1;
    /** 절대 건너뛰기 불가. */
    public static final int MODE_NOSKIP = 2;

    private CutscenePackets() {}

    /** 서버 → 클라: "이 컷신을 재생해". */
    public record PlayS2C(String id, int mode) implements CustomPacketPayload {
        public static final Type<PlayS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "cutscene_play"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlayS2C::id,
                ByteBufCodecs.VAR_INT, PlayS2C::mode,
                PlayS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 서버 → 클라: 서버 플러그인의 프로토콜 버전. 이 채널을 듣는 것 자체가 "이 모드는 버전 2 이상" 이라는 표시다. */
    public record HelloS2C(int protocol) implements CustomPacketPayload {
        public static final Type<HelloS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "story_v" + PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HelloS2C::protocol,
                HelloS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 클라 → 서버: 컷신 화면이 끝나기 전에 사라졌다 (사망 화면, 서버가 연 GUI, 월드 이동 등). 끝난 것이 아니다.
     * 서버는 잠시 뒤 같은 컷신을 다시 보낸다.
     */
    public record AbortC2S(String id) implements CustomPacketPayload {
        public static final Type<AbortC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "cutscene_abort"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AbortC2S> CODEC = StreamCodec.composite(
                SHORT_STRING, AbortC2S::id,
                AbortC2S::new);

        public AbortC2S {
            id = clip(id);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 클라 → 서버: 이 컷신·대화를 보여 줄 수 없다. {@link #sendContentMissing} 으로 보낸다. */
    public record StoryFailC2S(int kind, String id, String reason) implements CustomPacketPayload {
        public static final Type<StoryFailC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "story_fail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StoryFailC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StoryFailC2S::kind,
                SHORT_STRING, StoryFailC2S::id,
                SHORT_STRING, StoryFailC2S::reason,
                StoryFailC2S::new);

        public StoryFailC2S {
            id = clip(id);
            reason = clip(reason);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 서버 → 클라: "재생 중인 컷신을 멈춰". 내용 없음. */
    public record StopS2C() implements CustomPacketPayload {
        public static final StopS2C INSTANCE = new StopS2C();
        public static final Type<StopS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "cutscene_stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StopS2C> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 클라 → 서버: "이 컷신이 끝났어 (건너뛰었는지)". 퀘스트 연결용. */
    public record DoneC2S(String id, boolean skipped) implements CustomPacketPayload {
        public static final Type<DoneC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "cutscene_done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DoneC2S> CODEC = StreamCodec.composite(
                SHORT_STRING, DoneC2S::id,
                ByteBufCodecs.BOOL, DoneC2S::skipped,
                DoneC2S::new);

        public DoneC2S {
            id = clip(id);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Optional metadata channel: IDs only, never commands, paths, dialogue graphs or progress. */
    public record CatalogC2S(String cutscenes,String dialogues) implements CustomPacketPayload {
        public static final Type<CatalogC2S> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"story_catalog"));
        public static final StreamCodec<RegistryFriendlyByteBuf,CatalogC2S> CODEC=StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8320),CatalogC2S::cutscenes,ByteBufCodecs.stringUtf8(8320),CatalogC2S::dialogues,CatalogC2S::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ReloadRequestS2C(String token) implements CustomPacketPayload {
        public static final Type<ReloadRequestS2C> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"story_reload_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ReloadRequestS2C> CODEC=StreamCodec.composite(SHORT_STRING,ReloadRequestS2C::token,ReloadRequestS2C::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ReloadResultC2S(String token,int failures) implements CustomPacketPayload {
        public static final Type<ReloadResultC2S> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"story_reload_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ReloadResultC2S> CODEC=StreamCodec.composite(SHORT_STRING,ReloadResultC2S::token,ByteBufCodecs.VAR_INT,ReloadResultC2S::failures,ReloadResultC2S::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ReloadS2C(int kind) implements CustomPacketPayload {
        public static final Type<ReloadS2C> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"story_reload"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ReloadS2C> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,ReloadS2C::kind,ReloadS2C::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }

    // ---------------------------------------------------------------- 대화

    /**
     * 서버 → 클라: "이 대화를 열어". affinity = "npc=점수,npc=점수".
     * vars = 글자 바꾸기 목록 ("{player}" → 한글 닉네임 등). 항목은 \u001E, 키와 값은 \u001F 로 구분.
     */
    /** Server-authorized local playback; token is opaque and bound to the authenticated player. */
    public record DialogueSessionS2C(String id,String vars,String token) implements CustomPacketPayload {
        public static final Type<DialogueSessionS2C> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"dialogue_session"));
        public static final StreamCodec<RegistryFriendlyByteBuf,DialogueSessionS2C> CODEC=StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,DialogueSessionS2C::id,ByteBufCodecs.STRING_UTF8,DialogueSessionS2C::vars,
                SHORT_STRING,DialogueSessionS2C::token,DialogueSessionS2C::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** action 0=ready, 1=normal completion, 2=abort. No command text or player target. */
    public record DialogueSessionSignalC2S(int action,String id,String token,String ending) implements CustomPacketPayload {
        public static final Type<DialogueSessionSignalC2S> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE,"dialogue_session_signal"));
        public static final StreamCodec<RegistryFriendlyByteBuf,DialogueSessionSignalC2S> CODEC=StreamCodec.composite(
                ByteBufCodecs.VAR_INT,DialogueSessionSignalC2S::action,SHORT_STRING,DialogueSessionSignalC2S::id,
                SHORT_STRING,DialogueSessionSignalC2S::token,SHORT_STRING,DialogueSessionSignalC2S::ending,DialogueSessionSignalC2S::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record DialogueViewS2C(String id, String vars) implements CustomPacketPayload {
        public static final Type<DialogueViewS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueViewS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DialogueViewS2C::id, ByteBufCodecs.STRING_UTF8, DialogueViewS2C::vars, DialogueViewS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** 서버가 보내는 대화 내용 (dialogue.yml 글). 대화를 여는 패킷 바로 앞에 온다. */
    public record DialogueContentS2C(String id, String text) implements CustomPacketPayload {
        public static final Type<DialogueContentS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_content"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueContentS2C> CODEC = StreamCodec.of(
                (buf, v) -> { buf.writeUtf(v.id(), 64); buf.writeUtf(v.text(), kr.chacademy.story.dialogue.ServerDialogues.MAX_CHARS); },
                buf -> new DialogueContentS2C(buf.readUtf(64), buf.readUtf(kr.chacademy.story.dialogue.ServerDialogues.MAX_CHARS)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record DialogueRewardC2S(String key) implements CustomPacketPayload {
        public static final Type<DialogueRewardC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_reward"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueRewardC2S> CODEC = StreamCodec.composite(SHORT_STRING, DialogueRewardC2S::key, DialogueRewardC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record DialogueReadyC2S(String id) implements CustomPacketPayload {
        public static final Type<DialogueReadyC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueReadyC2S> CODEC = StreamCodec.composite(SHORT_STRING, DialogueReadyC2S::id, DialogueReadyC2S::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DialogueOpenS2C(String id, String affinity, String vars, String startScene, int startLine) implements CustomPacketPayload {
        public static final Type<DialogueOpenS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueOpenS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DialogueOpenS2C::id,
                ByteBufCodecs.STRING_UTF8, DialogueOpenS2C::affinity,
                ByteBufCodecs.STRING_UTF8, DialogueOpenS2C::vars,
                ByteBufCodecs.STRING_UTF8, DialogueOpenS2C::startScene,
                ByteBufCodecs.VAR_INT, DialogueOpenS2C::startLine,
                DialogueOpenS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 클라 → 서버: 지금 보고 있는 곳. 접속이 끊기거나 서버가 꺼져도 여기서부터 다시 연다. */
    public record DialogueProgressC2S(String id, String scene, int line) implements CustomPacketPayload {
        public static final Type<DialogueProgressC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_progress"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueProgressC2S> CODEC = StreamCodec.composite(
                SHORT_STRING, DialogueProgressC2S::id,
                SHORT_STRING, DialogueProgressC2S::scene,
                ByteBufCodecs.VAR_INT, DialogueProgressC2S::line,
                DialogueProgressC2S::new);

        public DialogueProgressC2S {
            id = clip(id);
            scene = clip(scene);
            line = Math.max(0, line);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 서버 → 클라: "대화를 닫아". 내용 없음. */
    public record DialogueStopS2C() implements CustomPacketPayload {
        public static final DialogueStopS2C INSTANCE = new DialogueStopS2C();
        public static final Type<DialogueStopS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueStopS2C> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 클라 → 서버: 선택지/장면 이벤트. 서버는 event 이름만 본다 (npc / add 는 예전 형식 자리만 남은 것, 4바이트 정수). */
    public record DialogueEventC2S(String id, String event, String npc, int add) implements CustomPacketPayload {
        public static final Type<DialogueEventC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueEventC2S> CODEC = StreamCodec.composite(
                SHORT_STRING, DialogueEventC2S::id,
                SHORT_STRING, DialogueEventC2S::event,
                SHORT_STRING, DialogueEventC2S::npc,
                ByteBufCodecs.INT, DialogueEventC2S::add,
                DialogueEventC2S::new);

        public DialogueEventC2S {
            id = clip(id);
            event = clip(event);
            npc = clip(npc);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 클라 → 서버: 대화가 끝남. */
    public record DialogueDoneC2S(String id, String lastScene) implements CustomPacketPayload {
        public static final Type<DialogueDoneC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "dialogue_done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueDoneC2S> CODEC = StreamCodec.composite(
                SHORT_STRING, DialogueDoneC2S::id,
                SHORT_STRING, DialogueDoneC2S::lastScene,
                DialogueDoneC2S::new);

        public DialogueDoneC2S {
            id = clip(id);
            lastScene = clip(lastScene);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
