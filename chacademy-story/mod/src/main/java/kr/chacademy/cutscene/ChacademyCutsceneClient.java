package kr.chacademy.cutscene;

import com.mojang.brigadier.arguments.StringArgumentType;
import kr.chacademy.cutscene.client.BgmPlayer;
import kr.chacademy.cutscene.client.CutscenePlayback;
import kr.chacademy.cutscene.client.SeenStore;
import kr.chacademy.cutscene.data.CutsceneLoader;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.story.dialogue.Affinity;
import kr.chacademy.story.dialogue.Dialogue;
import kr.chacademy.story.dialogue.DialogueLoader;
import kr.chacademy.story.dialogue.DialoguePresenter;
import kr.chacademy.story.dialogue.DialogueRunner;
import kr.chacademy.story.dialogue.TextVars;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.Map;

/**
 * 차카데미 스토리 모드 (컷신 + 대화).
 * <pre>
 * [서버] /대화관리 재생 준우 컷신 ch1_intro   → 컷신 재생 → 끝나면 cutscene_done
 * [서버] /대화관리 재생 준우 대화 ch1_wakeup   → 대화 열기 → 끝나면 서버 끝 명령 세션
 * </pre>
 * 대화창은 이 모드가 그리지 않는다: 대화 내용·흐름(DialogueRunner)만 갖고, 화면은 MagicCodex 대화창이 그린다 (DialoguePresenter).
 * /스토리도구 (개발용 로컬 미리보기) 는 클라이언트 파일과 로컬 기록만 사용한다.
 */
public class ChacademyCutsceneClient implements ClientModInitializer {
    public static final String MOD_ID = "chaca_story";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 채팅 명령으로 요청된 재생. 채팅창이 닫힌 다음 틱에 연다 (바로 열면 채팅창이 닫히면서 같이 닫힘). */
    private static Runnable pendingLocal = null;
    private static java.util.List<String> cutsceneIds=java.util.List.of(), dialogueIds=java.util.List.of();
    private static boolean catalogSent;

    private static void refreshIds() {
        cutsceneIds=java.util.List.copyOf(CutsceneLoader.listIds());
        dialogueIds=java.util.List.copyOf(DialogueLoader.listIds());
    }
    private static void sendCatalog() {
        if (!ClientPlayNetworking.canSend(CutscenePackets.CatalogC2S.TYPE)) return;
        ClientPlayNetworking.send(new CutscenePackets.CatalogC2S(
                String.join("\n",cutsceneIds.stream().limit(128).toList()),
                String.join("\n",dialogueIds.stream().limit(128).toList())));
        catalogSent=true;
    }
    private static int reloadContent(int kind,java.util.function.Consumer<Component> feedback) {
        boolean dialogue=kind==CutscenePackets.KIND_DIALOGUE;
        var result=dialogue
                ? kr.chacademy.cutscene.data.ContentReload.validate(DialogueLoader.rootDir(),"dialogue.yml",DialogueLoader::load)
                : kr.chacademy.cutscene.data.ContentReload.validate(CutsceneLoader.rootDir(),"cutscene.yml",CutsceneLoader::load);
        if(dialogue)for(String id:result.validIds())try{
            for(String warning:DialogueLoader.warnings(DialogueLoader.rootDir(),id))feedback.accept(Component.literal(id+": "+warning).withStyle(ChatFormatting.YELLOW));
        }catch(java.io.IOException e){feedback.accept(Component.literal(id+": "+e.getMessage()).withStyle(ChatFormatting.RED));}
        refreshIds();sendCatalog();
        feedback.accept(Component.literal((dialogue?"스토리 대화":"컷신")+" 리로드: 정상 "+result.validIds().size()+"개, 오류 "+result.errors().size()+"개.")
                .withStyle(result.errors().isEmpty()?ChatFormatting.GREEN:ChatFormatting.RED));
        result.errors().forEach((id,reason)->feedback.accept(Component.literal(id+": "+reason).withStyle(ChatFormatting.RED)));
        feedback.accept(Component.literal("현재 재생은 유지되며 변경은 다음 재생부터 적용됩니다. 완료·보상 기록은 유지됩니다."));
        if(dialogue) feedback.accept(Component.literal("종료·이벤트 명령 설정도 검사했습니다. 리로드로 명령이나 보상을 실행하지 않습니다."));
        return result.errors().isEmpty()?1:0;
    }


    @Override
    public void onInitializeClient() {
        kr.chacademy.story.dialogue.StoryJournal.install();
        // 스토리 진행 단계 HUD · 목표 포탈 · NPC 징표 (서버 ChacademyStory 의 quest_step / quest_update)
        kr.chacademy.story.quest.QuestClient.install();
        PayloadTypeRegistry.playS2C().register(CutscenePackets.ReloadRequestS2C.TYPE,CutscenePackets.ReloadRequestS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.ReloadResultC2S.TYPE,CutscenePackets.ReloadResultC2S.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.ReloadRequestS2C.TYPE,(payload,context)->context.client().execute(()->{
            java.util.function.Consumer<Component> feedback=message->{if(context.client().player!=null)context.client().player.displayClientMessage(message,false);};
            int failures=0;
            if(reloadContent(CutscenePackets.KIND_DIALOGUE,feedback)==0)failures++;
            if(reloadContent(CutscenePackets.KIND_CUTSCENE,feedback)==0)failures++;
            if(ClientPlayNetworking.canSend(CutscenePackets.ReloadResultC2S.TYPE))ClientPlayNetworking.send(new CutscenePackets.ReloadResultC2S(payload.token(),failures));
        }));
        // 서버가 보내는 대화 내용 (대사는 서버에서만 관리) · 초상화는 리소스팩 chacademia_npc (magiccodex:textures/gui/dialogue/<이름>.png)
        PayloadTypeRegistry.playS2C().register(CutscenePackets.DialogueContentS2C.TYPE,CutscenePackets.DialogueContentS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.DialogueContentS2C.TYPE,(payload,context)->
                kr.chacademy.story.dialogue.ServerDialogues.put(payload.id(),payload.text())); // 바로 넣는다 (뒤따르는 여는 패킷보다 먼저 있어야 함, 맵은 스레드 안전)
        kr.chacademy.story.dialogue.DialogueRunner.packPortrait=name->{
            var mc=Minecraft.getInstance();
            return mc.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("magiccodex","textures/gui/dialogue/"+name+".png")).isPresent();
        };
        PayloadTypeRegistry.playS2C().register(CutscenePackets.DialogueSessionS2C.TYPE,CutscenePackets.DialogueSessionS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueSessionSignalC2S.TYPE,CutscenePackets.DialogueSessionSignalC2S.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.DialogueSessionS2C.TYPE,(payload,context)->{
            pendingLocal=()->openLocalDialogue(payload.id(),TextVars.decode(payload.vars()),true,payload.token());
        });
        PayloadTypeRegistry.playC2S().register(CutscenePackets.CatalogC2S.TYPE,CutscenePackets.CatalogC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePackets.ReloadS2C.TYPE,CutscenePackets.ReloadS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.ReloadS2C.TYPE,(payload,context)->context.client().execute(()-> {
            if(payload.kind()!=CutscenePackets.KIND_CUTSCENE && payload.kind()!=CutscenePackets.KIND_DIALOGUE)return;
            reloadContent(payload.kind(),message->{if(context.client().player!=null)context.client().player.displayClientMessage(message,false);});
        }));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->{refreshIds();catalogSent=false;});
        PayloadTypeRegistry.playS2C().register(CutscenePackets.PlayS2C.TYPE, CutscenePackets.PlayS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePackets.StopS2C.TYPE, CutscenePackets.StopS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DoneC2S.TYPE, CutscenePackets.DoneC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePackets.DialogueOpenS2C.TYPE, CutscenePackets.DialogueOpenS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePackets.DialogueViewS2C.TYPE, CutscenePackets.DialogueViewS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueRewardC2S.TYPE, CutscenePackets.DialogueRewardC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueReadyC2S.TYPE, CutscenePackets.DialogueReadyC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(CutscenePackets.DialogueStopS2C.TYPE, CutscenePackets.DialogueStopS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueEventC2S.TYPE, CutscenePackets.DialogueEventC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueDoneC2S.TYPE, CutscenePackets.DialogueDoneC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.DialogueProgressC2S.TYPE, CutscenePackets.DialogueProgressC2S.CODEC);
        // 프로토콜 2: 버전 알림 (이 채널을 듣는 것이 서버에게 "새 모드" 라는 표시), 컷신 중단, 스토리 파일 없음
        PayloadTypeRegistry.playS2C().register(CutscenePackets.HelloS2C.TYPE, CutscenePackets.HelloS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.AbortC2S.TYPE, CutscenePackets.AbortC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CutscenePackets.StoryFailC2S.TYPE, CutscenePackets.StoryFailC2S.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.HelloS2C.TYPE,
                (payload, context) -> CutscenePlayback.serverHello(payload.protocol()));

        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.PlayS2C.TYPE,
                (payload, context) -> play(payload.id(), payload.mode(), true));
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.StopS2C.TYPE,
                (payload, context) -> stopCurrent());
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.DialogueOpenS2C.TYPE,
                (payload, context) -> openDialogue(payload.id(), Affinity.decode(payload.affinity()), TextVars.decode(payload.vars()), true,
                        payload.startScene(), payload.startLine()));
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.DialogueViewS2C.TYPE,
                (payload, context) -> { pendingLocal = () -> openLocalDialogue(payload.id(), TextVars.decode(payload.vars()), true); });
        ClientPlayNetworking.registerGlobalReceiver(CutscenePackets.DialogueStopS2C.TYPE,
                (payload, context) -> stopDialogue());
        // 접속이 끊기면 대화 화면 정리 (다시 접속하면 서버가 저장된 곳부터 다시 연다)
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    pendingLocal = null;
                    catalogSent=false;
                    kr.chacademy.story.dialogue.ServerDialogues.clear();
                    stopDialogue();
                    CutscenePlayback.disconnect();
                }));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BgmPlayer.tick();
            if(client.player!=null && !catalogSent)sendCatalog();
            // 게임 메뉴(ESC) 때문에 내려간 컷신을 다시 띄우고, 보여 줄 수 있게 된 재생 요청을 시작한다
            CutscenePlayback.tick(client);
            // 진행 중인 스토리 대화: 끝나기 전에는 화면이 비면 MagicCodex 대화창을 다시 띄운다 (강제로 닫을 수 없음)
            DialoguePresenter.tick(client);
            if (pendingLocal != null && !(client.screen instanceof ChatScreen)) {
                Runnable r = pendingLocal;
                pendingLocal = null;
                r.run();
            }
        });

        try {
            Files.createDirectories(CutsceneLoader.rootDir());
            Files.createDirectories(DialogueLoader.rootDir());
        } catch (Exception ignored) {
        }

        refreshIds();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 개발용 로컬 미리보기 하나만 (서버 /대화관리·/테스트 스토리 와 이름이 겹치지 않게)
            dispatcher.register(ClientCommandManager.literal("스토리도구")
                    .then(ClientCommandManager.literal("재생").then(ClientCommandManager.argument("ID", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                dialogueIds.forEach(b::suggest);
                                cutsceneIds.forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .executes(ctx -> playAny(StringArgumentType.getString(ctx, "ID")))))
                    .then(ClientCommandManager.literal("대화").then(ClientCommandManager.argument("대화", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                dialogueIds.forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "대화");
                                pendingLocal = () -> openLocalDialogue(id, Map.of(), false);
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("컷신").then(ClientCommandManager.argument("컷신", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                cutsceneIds.forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "컷신");
                                pendingLocal = () -> play(id, CutscenePackets.MODE_SKIP, false);
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("목록").executes(ctx -> {
                        refreshIds();
                        ctx.getSource().sendFeedback(Component.literal("대화 " + dialogueIds.size() + "개: " + String.join(", ", dialogueIds)));
                        ctx.getSource().sendFeedback(Component.literal("컷신 " + cutsceneIds.size() + "개: " + String.join(", ", cutsceneIds)));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("새로고침").executes(ctx -> {
                        int ok = reloadContent(CutscenePackets.KIND_DIALOGUE, ctx.getSource()::sendFeedback);
                        ok &= reloadContent(CutscenePackets.KIND_CUTSCENE, ctx.getSource()::sendFeedback);
                        return ok;
                    }))
                    .then(ClientCommandManager.literal("본기록초기화").executes(ctx -> {
                        SeenStore.reset();
                        ctx.getSource().sendFeedback(Component.literal("본 컷신 기록을 지웠어요."));
                        return 1;
                    })));
        });

        LOGGER.info("Chacademy Story 로드됨 (컷신: {}, 대화: {})", CutsceneLoader.rootDir(), DialogueLoader.rootDir());
    }

    /** /스토리도구 재생 &lt;ID&gt;: 대화 ID 면 대화, 컷신 ID 면 컷신 (둘 다 있으면 대화). */
    private static int playAny(String id) {
        refreshIds();
        if (dialogueIds.contains(id)) {
            pendingLocal = () -> openLocalDialogue(id, Map.of(), false);
            return 1;
        }
        if (cutsceneIds.contains(id)) {
            pendingLocal = () -> play(id, CutscenePackets.MODE_SKIP, false);
            return 1;
        }
        error("대화·컷신을 찾을 수 없어요: " + id);
        return 0;
    }

    // ---------------------------------------------------------------- 컷신

    /**
     * 컷신 재생. 렌더 스레드에서 호출할 것. 파일 읽기는 작업 스레드에서 하고 화면은 바로 뜬다 (CutscenePlayback / CutsceneScreen).
     * 끝나면 cutscene_done, 화면이 중간에 사라지면 cutscene_abort, 파일이 없거나 깨졌으면 story_fail 을 서버로 보낸다.
     */
    public static void play(String id, int mode, boolean fromServer) {
        CutscenePlayback.play(id, mode, fromServer);
    }

    public static void stopCurrent() {
        CutscenePlayback.stop();
    }

    // ---------------------------------------------------------------- 대화

    /** 대화 열기. fromServer 면 선택지 이벤트와 끝을 서버로 알린다 (서버가 명령어 실행). */
    private static void stopDialogue() {
        pendingLocal=null;DialoguePresenter.stop();activeLocalToken="";
    }

    /** Default path: client file, client records, ordinary player commands only. */
    private static String activeLocalToken="";
    private static void sessionSignal(int action,String id,String token,String ending){
        if(!token.isEmpty()&&ClientPlayNetworking.canSend(CutscenePackets.DialogueSessionSignalC2S.TYPE))
            ClientPlayNetworking.send(new CutscenePackets.DialogueSessionSignalC2S(action,id,token,ending));
    }
    private static void openLocalDialogue(String id,Map<String,String> vars,boolean acknowledge){openLocalDialogue(id,vars,acknowledge,"");}
    private static void openLocalDialogue(String id,Map<String,String> vars,boolean acknowledge,String token) {
        if(id==null||!DialogueLoader.ID_PATTERN.matcher(id).matches()) { error("대화 id 형식이 잘못되었습니다.");return; }
        if(DialoguePresenter.isActive()) {
            // Repeated requests never replace an active runner or issue its effects twice.
            if(acknowledge && DialoguePresenter.isLocal(id)&&token.equals(activeLocalToken)) {
                if(token.isEmpty())sendLocalReady(id);else sessionSignal(0,id,token,"");
            }
            else if(acknowledge){sessionSignal(2,id,token,"");reportDialogueFailure(id,"busy");}
            return;
        }
        if(!DialoguePresenter.available()) { sessionSignal(2,id,token,"");DialoguePresenter.warnUnavailable();if(acknowledge)reportDialogueFailure(id,CutscenePackets.FAIL_MAGICCODEX);return; }
        var client=Minecraft.getInstance();if(client.player==null)return;
        try {
            var dialogue=DialogueLoader.load(id);
            for(String warning:DialogueLoader.warnings(DialogueLoader.rootDir(),id))client.player.displayClientMessage(Component.literal(id+": "+warning).withStyle(ChatFormatting.YELLOW),false);
            var ledger=new kr.chacademy.story.dialogue.LocalDialogueStore(DialogueLoader.rootDir().resolve("_progress").resolve(client.player.getUUID()+".properties"));
            var signals=new kr.chacademy.story.dialogue.DialogueOpenSignals();
            // 선택지 조건(need)은 서버가 보낸 MagicCodex 호감도만 쓴다 (이 PC 에 저장한 값 없음)
            var serverAffinity=kr.chacademy.story.dialogue.Affinity.fromVars(vars);
            var localVars=TextVars.withLocal(kr.chacademy.story.dialogue.Affinity.withoutAffinity(vars));
            var queuedAffinity=new java.util.HashSet<String>();
            var runner=new DialogueRunner(dialogue,DialogueLoader.folder(id),serverAffinity,localVars,new DialogueRunner.Listener(){
                @Override public boolean affinity(String dialogueId,String key,java.util.List<Dialogue.AffinityChange> changes){
                    // Defer writes until UI accepted; constructor-time changes still affect this frame's local branches.
                    if(ledger.done(dialogueId)||ledger.hasAffinity(dialogueId,key)||!queuedAffinity.add(key))return false;
                    signals.send(()->{try{ledger.claimAffinity(dialogueId,key,changes);}catch(java.io.IOException e){signals.reject();localRecordFailure(e);}});
                    return true;
                }
                @Override public void event(String dialogueId,String event,String npc,int add){
                    if(event.isEmpty())return;
                    signals.send(()->{try{if(ledger.claimEvent(dialogueId,event))runPlayerCommands(dialogue.commands().events().getOrDefault(event,java.util.List.of()));}catch(java.io.IOException e){signals.reject();localRecordFailure(e);}});
                }
                @Override public void aborted(String dialogueId){signals.reject();sessionSignal(2,dialogueId,token,"");activeLocalToken="";}
                @Override public void done(String dialogueId,String lastScene){
                    signals.send(()->{
                        // Console ending commands are per authorized playback, independent of lifetime reward records.
                        sessionSignal(1,dialogueId,token,lastScene);activeLocalToken="";
                        try{if(ledger.complete(dialogueId)){
                            runFirstRewards(dialogue.commands().endings().getOrDefault(lastScene,java.util.List.of()));
                            runFirstRewards(dialogue.commands().end());
                        }}catch(java.io.IOException e){signals.reject();localRecordFailure(e);}
                    });
                }
            });
            if(!DialoguePresenter.start(runner,false)){signals.reject();sessionSignal(2,id,token,"");if(acknowledge)reportDialogueFailure(id,CutscenePackets.FAIL_MAGICCODEX);return;}
            boolean firstJournal=ledger.journalState(id).equals("available");
            try{ledger.journalSeen(dialogue);if(firstJournal)kr.chacademy.story.dialogue.StoryJournal.granted(dialogue); }catch(java.io.IOException e){signals.reject();DialoguePresenter.stop();throw e;}
            activeLocalToken=token;
            try{signals.accept();}catch(java.io.UncheckedIOException e){DialoguePresenter.stop();localRecordFailure(e);if(acknowledge)reportDialogueFailure(id,"local_record");return;}
            if(signals.isRejected()){if(acknowledge)reportDialogueFailure(id,"local_record");return;}
            if(acknowledge){if(token.isEmpty())sendLocalReady(id);else sessionSignal(0,id,token,"");}
        }catch(Exception e){sessionSignal(2,id,token,"");LOGGER.warn("로컬 대화 열기 실패: {}",id,e);error("대화를 열지 못했어요: "+id);if(acknowledge)reportDialogueFailure(id,CutscenePackets.FAIL_BROKEN);}
    }
    private static void sendLocalReady(String id){if(ClientPlayNetworking.canSend(CutscenePackets.DialogueReadyC2S.TYPE))ClientPlayNetworking.send(new CutscenePackets.DialogueReadyC2S(id));}
    private static void localRecordFailure(Exception e){DialoguePresenter.stop();LOGGER.warn("로컬 대화 기록 저장 실패; 추가 명령을 실행하지 않습니다",e);error("대화 기록을 저장하지 못했습니다. 명령 실행을 중단했습니다.");}
    private static void runFirstRewards(java.util.List<String> commands){
        runPlayerCommands(commands.stream().filter(c->c.startsWith("chaca_reward ")).toList());
    }
    private static void runPlayerCommands(java.util.List<String> commands){
        var client=Minecraft.getInstance();if(client.player==null||client.getConnection()==null)return;
        String account=client.player.getGameProfile().getName();
        String nickname=kr.chacademy.story.compat.MagicCodexClientLink.nickname(account);
        for(String raw:commands){String command=kr.chacademy.story.dialogue.DialogueCommands.expand(raw,account,nickname);
            if(command.startsWith("chaca_reward ")) {
                String key=command.substring("chaca_reward ".length());
                if(!java.util.Set.of("ch1-2.e_s11","ch1-3.end").contains(key)){error("알 수 없는 스토리 보상입니다.");continue;}
                if(ClientPlayNetworking.canSend(CutscenePackets.DialogueRewardC2S.TYPE))ClientPlayNetworking.send(new CutscenePackets.DialogueRewardC2S(key));
                else error("스토리 보상 연결을 사용할 수 없습니다. 관리자에게 알려 주세요.");
                continue;
            }
            // All other commands retain vanilla player permission checks.
            client.getConnection().sendCommand(command);
        }
    }

    public static void openDialogue(String id, Map<String, Integer> affinity, Map<String, String> vars, boolean fromServer,
                                    String startScene, int startLine) {
        id = id == null ? "" : id.strip();
        if (!DialogueLoader.ID_PATTERN.matcher(id).matches()) { error("대화 id는 소문자, 숫자, _, - 1~64자입니다."); return; }
        if (!fromServer && DialoguePresenter.serverOwned()) { error("서버 스토리가 끝난 뒤 로컬 대화를 열어 주세요."); return; }

        if (!DialoguePresenter.available()) {
            // MagicCodex 대화창이 없으면 열지 않는다 (자체 창으로 대신하지 않음). 대화 파일이 없을 때처럼 끝 신호도 보내지 않는다
            LOGGER.warn("대화 {} 를 열 수 없음: MagicCodex 모드가 없거나 대화창 창구 버전이 낮음", id);
            DialoguePresenter.warnUnavailable();
            // 서버에는 "보여 줄 수 없음" 을 알린다 (끝난 것으로 치지 않고, 콘솔에 남아 운영자가 알 수 있게)
            if (fromServer) reportDialogueFailure(id, CutscenePackets.FAIL_MAGICCODEX);
            return;
        }
        Dialogue dialogue;
        try {
            dialogue = DialogueLoader.load(id);
        } catch (Exception e) {
            LOGGER.warn("대화 {} 불러오기 실패", id, e);
            error("대화를 찾을 수 없어요: " + id);
            if (fromServer) {
                boolean missing = !DialogueLoader.exists(id);
                reportDialogueFailure(id, missing ? CutscenePackets.FAIL_MISSING : CutscenePackets.FAIL_BROKEN);
            }
            return;
        }
        stopDialogue();
        LOGGER.info("대화 시작: {} (장면 {}개)", id, dialogue.scenes().size());
        // 표정 그림은 MagicCodex 대화창이 폴더에서 직접 (렌더 스레드 밖에서) 읽는다
        var signals = new kr.chacademy.story.dialogue.DialogueOpenSignals();
        DialogueRunner runner = new DialogueRunner(dialogue, DialogueLoader.folder(id), affinity, TextVars.withLocal(vars), new DialogueRunner.Listener() {
            @Override
            public void event(String dialogueId, String event, String npc, int add) {
                if (fromServer && ClientPlayNetworking.canSend(CutscenePackets.DialogueEventC2S.TYPE)) {
                    signals.send(() -> ClientPlayNetworking.send(new CutscenePackets.DialogueEventC2S(dialogueId, event, npc, add)));
                }
            }

            @Override
            public void done(String dialogueId, String lastScene) {
                if (fromServer && ClientPlayNetworking.canSend(CutscenePackets.DialogueDoneC2S.TYPE)) {
                    signals.send(() -> ClientPlayNetworking.send(new CutscenePackets.DialogueDoneC2S(dialogueId, lastScene)));
                }
            }

            @Override
            public void progress(String dialogueId, String scene, int line) {
                if (fromServer && ClientPlayNetworking.canSend(CutscenePackets.DialogueProgressC2S.TYPE)) {
                    signals.send(() -> ClientPlayNetworking.send(new CutscenePackets.DialogueProgressC2S(dialogueId, scene, line)));
                }
            }
        }, startScene, startLine);
        if (!DialoguePresenter.start(runner, fromServer)) {
            signals.reject();
            if (fromServer) reportDialogueFailure(id, CutscenePackets.FAIL_MAGICCODEX);
            return;
        }
        signals.accept();
    }

    /** 서버가 연 대화를 보여 줄 수 없을 때 (파일 없음·깨짐, MagicCodex 없음). 컷신과 같은 story_fail 신호를 쓴다. */
    private static void reportDialogueFailure(String id, String reason) {
        try {
            CutscenePackets.sendContentMissing(CutscenePackets.KIND_DIALOGUE, id, reason);
        } catch (RuntimeException e) {
            LOGGER.warn("대화 실패를 서버에 알리지 못함", e);
        }
    }

    private static void error(String message) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), false);
    }
}
