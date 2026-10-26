package kr.chacademy.cutscene.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import kr.chacademy.cutscene.ChacademyCutsceneClient;
import kr.chacademy.cutscene.data.Cutscene;
import kr.chacademy.cutscene.data.CutsceneLoader;
import kr.chacademy.cutscene.net.CutscenePackets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 컷신 재생 화면.
 * <ul>
 *   <li>위아래 레터박스가 내려옴</li>
 *   <li>장면마다 지정한 지점으로 천천히 줌, 이미지가 여러 장이면 반복 재생</li>
 *   <li>잉크 번짐 / 페이드 / 컷 전환</li>
 *   <li>아래 띠에 자막이 한 글자씩</li>
 *   <li>오른쪽 위 &gt;&gt; 버튼으로 2배속, 건너뛰기 가능하면 스페이스 2초 꾹</li>
 *   <li>ESC = 게임 메뉴 (언제든 접속을 끊을 수 있게). 메뉴가 떠 있는 동안 컷신 시계와 배경음이 멈추고, 닫으면 이어진다.
 *       건너뛰기가 아니다 — noskip 컷신은 여전히 끝까지 봐야 한다.</li>
 * </ul>
 * 불러오기: 화면은 바로 뜨고 (검은 화면 + 레터박스), yml·PNG·ogg 는 작업 스레드에서 읽는다.
 * 텍스처는 "지금 장면 + 다음 장면" 만 올려 두고 지나간 장면은 내린다. 첫 장면이 준비되면 시계가 돌기 시작한다.
 */
public class CutsceneScreen extends Screen {
    private static final double SKIP_HOLD_SECONDS = 2.0;
    /** 불러오기 (또는 다음 장면 그림) 를 이만큼 기다려도 안 되면 실패로 끝낸다. */
    private static final double LOAD_TIMEOUT_SECONDS = 20.0;
    /** 잉크 전환 텍스처를 다시 계산하는 최소 간격 (초). 화면 전체를 CPU 로 칠하므로 매 프레임 하지 않는다. */
    private static final double INK_UPDATE_INTERVAL = 1.0 / 30.0;

    public record Result(String id, boolean skipped, boolean completed) {
    }

    /** 화면이 끝나는 세 가지 방법. 모두 렌더 스레드에서 한 번만 불린다. */
    public interface Listener {
        /** 끝까지 봤거나 건너뜀. */
        void done(Result result);

        /** 끝나기 전에 다른 화면이 덮어서 사라짐 (사망 화면, 서버 GUI 등). */
        void aborted(String id);

        /** 보여 줄 수 없음. reason = CutscenePackets.FAIL_*, detail = 사람이 읽을 설명. */
        void failed(String id, String reason, String detail);
    }

    /** 작업 스레드에서 읽은 것. */
    private static final class Loaded {
        final Cutscene cutscene;
        final boolean seen;
        private BgmPlayer.Pcm bgm;

        Loaded(Cutscene cutscene, boolean seen, BgmPlayer.Pcm bgm) {
            this.cutscene = cutscene;
            this.seen = seen;
            this.bgm = bgm;
        }

        synchronized BgmPlayer.Pcm takeBgm() {
            BgmPlayer.Pcm p = bgm;
            bgm = null;
            return p;
        }

        synchronized void discard() {
            if (bgm != null) bgm.free();
            bgm = null;
        }
    }

    private final String id;
    private final int mode;
    private final Listener listener;
    private final CompletableFuture<Loaded> loading;
    private Loaded loaded;

    private Cutscene cutscene;
    private CutsceneTextures textures;
    private boolean skippable;
    private ResourceLocation subtitleFont;

    /** 첫 장면까지 준비돼서 시계가 돌기 시작함. */
    private boolean started = false;
    private double waited = 0;
    private double time = 0;
    private long lastNanos = -1;
    private boolean doubleSpeed = false;
    private double skipHold = 0;
    private boolean ended = false;
    /** ESC 로 게임 메뉴를 열어 잠깐 내려가 있음 (끝난 것이 아님). */
    private boolean suspended = false;

    private int windowScene = -2;
    private boolean windowTransition = false;

    private int transitionScene = -1;
    private InkLayer ink;
    private int inkMaskScene = -1;
    private CompletableFuture<float[]> inkMask;

    private int speedX0, speedY0, speedX1, speedY1;

    /** 타자기 소리: 어느 자막의 몇 글자까지 소리를 냈는지. */
    private Cutscene.Line soundLine;
    private int soundShown = 0;
    private long lastTickNanos = 0;
    private SoundEvent typeSound;
    /** default = ChacaNPC/MagicCodex 대화창과 같은 소리 (버튼 클릭음, 높이 1.8, 크기 0.07, 45ms 간격). */
    private boolean dialogueTick;

    /** 화면을 만들면 바로 작업 스레드에서 읽기 시작한다. mode = CutscenePackets.MODE_*. */
    public CutsceneScreen(String id, int mode, Listener listener) {
        super(Component.literal(id));
        this.id = id;
        this.mode = mode;
        this.listener = listener;
        this.loading = CompletableFuture.supplyAsync(() -> load(id), CutsceneTextures.WORKER);
        BgmPlayer.holdVanillaMusic(true);
    }

    public String cutsceneId() {
        return id;
    }

    /** 작업 스레드: yml 읽기, 그림 파일이 다 있는지 확인, 본 적 있는지, 배경음 디코딩. */
    private static Loaded load(String id) {
        try {
            Cutscene c = CutsceneLoader.load(id);
            Path folder = CutsceneLoader.folder(id);
            Set<String> names = new HashSet<>();
            for (Cutscene.Scene s : c.scenes()) names.addAll(s.images());
            for (String n : names) {
                if (!Files.isRegularFile(folder.resolve(n))) throw new NoSuchFileException(n, null, "이미지 없음");
            }
            BgmPlayer.Pcm bgm = null;
            if (!c.bgm().isEmpty()) {
                try {
                    bgm = BgmPlayer.decode(folder.resolve(c.bgm()));
                } catch (IOException | RuntimeException e) {
                    // 배경음은 없어도 컷신은 보여 준다
                    ChacademyCutsceneClient.LOGGER.warn("컷신 {} 배경음을 읽지 못함", id, e);
                }
            }
            return new Loaded(c, SeenStore.hasSeen(id), bgm);
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    // ---------------------------------------------------------------- 불러오기

    private List<String> images(int scene) {
        return scene < 0 || scene >= cutscene.scenes().size() ? List.of() : cutscene.scenes().get(scene).images();
    }

    /** 준비가 끝났으면 true. 아직이면 false (검은 화면을 그린다). 실패하면 화면을 닫고 false. */
    private boolean prepare(double dt) {
        waited += dt;
        if (cutscene == null) {
            if (!loading.isDone()) {
                if (waited > LOAD_TIMEOUT_SECONDS) fail(CutscenePackets.FAIL_TIMEOUT, "불러오는 데 너무 오래 걸림");
                return false;
            }
            try {
                loaded = loading.join();
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                ChacademyCutsceneClient.LOGGER.warn("컷신 {} 불러오기 실패", id, cause);
                fail(cause instanceof NoSuchFileException ? CutscenePackets.FAIL_MISSING : CutscenePackets.FAIL_BROKEN, String.valueOf(cause.getMessage()));
                return false;
            }
            adopt(loaded.cutscene);
        }
        textures.pump(2);
        if (textures.failure() != null) {
            fail(CutscenePackets.FAIL_BROKEN, textures.failure());
            return false;
        }
        if (!textures.ready(images(0))) {
            if (waited > LOAD_TIMEOUT_SECONDS) fail(CutscenePackets.FAIL_TIMEOUT, "그림을 불러오는 데 너무 오래 걸림");
            return false;
        }
        BgmPlayer.Pcm bgm = loaded.takeBgm();
        if (bgm != null) {
            try {
                BgmPlayer.start(bgm, cutscene.bgmVolume(), cutscene.bgmStart());
            } catch (IOException | RuntimeException e) {
                ChacademyCutsceneClient.LOGGER.warn("컷신 {} 배경음을 재생하지 못함", id, e);
            }
        }
        ChacademyCutsceneClient.LOGGER.info("컷신 재생: {} (장면 {}개, {}초)", id, cutscene.scenes().size(), cutscene.totalDuration());
        started = true;
        waited = 0;
        return true;
    }

    private void adopt(Cutscene c) {
        this.cutscene = c;
        this.textures = new CutsceneTextures("cutscene/" + id, CutsceneLoader.folder(id));
        this.skippable = switch (mode) {
            case CutscenePackets.MODE_SKIP -> true;
            case CutscenePackets.MODE_NOSKIP -> false;
            default -> loaded.seen;
        };
        String weight = switch (c.font()) {
            case "light", "l" -> "l";
            case "bold", "b" -> "b";
            default -> "m";
        };
        this.subtitleFont = ResourceLocation.fromNamespaceAndPath("chaca_story", "subtitle_" + weight);
        String ts = c.typeSound();
        this.dialogueTick = ts.equalsIgnoreCase("default");
        if (ts.equalsIgnoreCase("none") || ts.isEmpty()) {
            this.typeSound = null;
        } else if (dialogueTick) {
            this.typeSound = SoundEvents.UI_BUTTON_CLICK.value();
        } else {
            ResourceLocation soundId = ResourceLocation.tryParse(ts);
            this.typeSound = soundId == null ? null : SoundEvent.createVariableRangeEvent(soundId);
        }
        textures.request(images(0));
        textures.request(images(1));
    }

    /** 지금 장면 + 다음 장면 (+ 전환 중이면 앞 장면) 만 남기고 나머지 텍스처를 내린다. 다음 장면을 미리 읽기 시작한다. */
    private void updateWindow(int index, boolean inTransition) {
        if (index == windowScene && inTransition == windowTransition) return;
        windowScene = index;
        windowTransition = inTransition;
        textures.request(images(index + 1));
        Set<String> keep = new HashSet<>(images(index));
        keep.addAll(images(index + 1));
        if (inTransition) keep.addAll(images(index - 1));
        textures.retain(keep);
        prepareInkMask(index + 1);
    }

    /** 다음 장면이 잉크 전환이면 번짐 지도를 작업 스레드에서 미리 계산해 둔다 (전환 첫 프레임이 멈칫하지 않게). */
    private void prepareInkMask(int scene) {
        if (scene == inkMaskScene || scene < 0 || scene >= cutscene.scenes().size()) return;
        Cutscene.Scene s = cutscene.scenes().get(scene);
        if (!"ink".equals(transitionKind(s)) || s.transitionTime() <= 0) return;
        CutsceneTextures.Tex first = textures.get(s.images().get(0));
        if (first == null) return; // 그림 크기를 알아야 한다: 올라온 뒤 다시 불린다
        inkMaskScene = scene;
        int w = first.width(), h = first.height(), seed = scene + 1;
        double cx = s.zoomX(), cy = s.zoomY();
        inkMask = CompletableFuture.supplyAsync(() -> InkMask.upscale(InkMask.build(w, h, cx, cy, seed), w, h), CutsceneTextures.WORKER);
    }

    private static String transitionKind(Cutscene.Scene s) {
        return switch (s.transition()) {
            case "cut", "fade" -> s.transition();
            default -> "ink";
        };
    }

    private void drawLoading(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0xFF000000);
        int bar = (int) Math.round(this.height * 0.14);
        g.fill(0, 0, this.width, bar, 0xFF0B0B0B);
        g.fill(0, this.height - bar, this.width, this.height, 0xFF0B0B0B);
    }

    // ---------------------------------------------------------------- 진행

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (ended) return;
        long now = System.nanoTime();
        if (suspended) {
            // 게임 메뉴에서 돌아옴: 멈췄던 곳부터
            suspended = false;
            lastNanos = -1;
            BgmPlayer.resume();
            BgmPlayer.holdVanillaMusic(true);
        }
        double realDt = lastNanos < 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (!started) {
            if (!prepare(realDt)) {
                if (!ended) drawLoading(g);
                return;
            }
        }
        textures.pump(1);
        if (textures.failure() != null) {
            fail(CutscenePackets.FAIL_BROKEN, textures.failure());
            return;
        }

        // 지금 장면 찾기
        int count = cutscene.scenes().size();
        int index = 0;
        double sceneStart = 0;
        for (int i = 0; i < count; i++) {
            double d = cutscene.scenes().get(i).duration();
            if (time < sceneStart + d || i == count - 1) {
                index = i;
                break;
            }
            sceneStart += d;
        }
        Cutscene.Scene scene = cutscene.scenes().get(index);

        // 시계. 다음 장면 그림이 아직 안 올라왔으면 이 장면 끝에서 기다린다
        double step = realDt * (doubleSpeed ? 2 : 1);
        double sceneEnd = sceneStart + scene.duration();
        if (index + 1 < count && time + step >= sceneEnd && !textures.ready(images(index + 1))) {
            time = Math.max(time, Math.min(time + step, sceneEnd - 0.001));
            waited += realDt;
            if (waited > LOAD_TIMEOUT_SECONDS) {
                fail(CutscenePackets.FAIL_TIMEOUT, "다음 장면 그림을 불러오는 데 너무 오래 걸림");
                return;
            }
        } else {
            time += step;
            waited = 0;
        }

        if (skippable && minecraft != null
                && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) {
            skipHold += realDt;
            if (skipHold >= SKIP_HOLD_SECONDS) {
                finish(true);
                return;
            }
        } else {
            skipHold = Math.max(0, skipHold - realDt * 3);
        }

        if (time >= cutscene.totalDuration()) {
            finish(false);
            return;
        }
        updateBgm();

        int w = this.width, h = this.height;
        g.fill(0, 0, w, h, 0xFF000000);

        double local = Math.max(0, Math.min(time - sceneStart, scene.duration()));
        String kind = transitionKind(scene);
        boolean inTransition = !"cut".equals(kind) && scene.transitionTime() > 0 && local < scene.transitionTime();
        updateWindow(index, inTransition);
        if (inkMaskScene != index + 1) prepareInkMask(index + 1);

        if (inTransition) {
            if (index > 0) {
                Cutscene.Scene prev = cutscene.scenes().get(index - 1);
                drawScene(g, prev, frameName(prev, time), prev.duration(), null, 0xFFFFFFFF);
            }
            double p = easeInOut(local / scene.transitionTime());
            if ("fade".equals(kind)) {
                // 페이드는 텍스처를 따로 만들지 않고 그릴 때 투명도만 준다
                int a = Math.max(0, Math.min(255, (int) Math.round(p * 255)));
                drawScene(g, scene, frameName(scene, time), local, null, (a << 24) | 0xFFFFFF);
            } else {
                InkLayer layer = inkFor(index, scene);
                if (layer != null) {
                    layer.update(p, now);
                    drawScene(g, scene, scene.images().get(0), local, layer.tex, 0xFFFFFFFF);
                } else {
                    drawScene(g, scene, frameName(scene, time), local, null, 0xFFFFFFFF);
                }
            }
        } else {
            drawScene(g, scene, frameName(scene, time), local, null, 0xFFFFFFFF);
            dropInk();
        }

        // 레터박스
        double barFull = h * cutscene.letterbox();
        double barK = cutscene.letterboxTime() <= 0 ? 1 : easeInOut(Math.min(1, time / cutscene.letterboxTime()));
        int bar = (int) Math.round(barFull * barK);
        if (bar > 0) {
            g.fill(0, 0, w, bar, 0xFF0B0B0B);
            g.fill(0, h - bar, w, h, 0xFF0B0B0B);
        }

        drawSubtitle(g, scene, local, barFull, w, h);

        // 마지막 암전
        double over = time - cutscene.scenesDuration();
        if (over > 0 && cutscene.endFade() > 0) {
            int a = (int) Math.round(255 * Math.min(1, over / cutscene.endFade()));
            g.fill(0, 0, w, h, (a << 24));
        }

        drawSpeedButton(g, mouseX, mouseY, w, Math.max(bar, 14));
        if (skippable) drawSkipRing(g, w, h, Math.max(bar, 22));
    }

    private String frameName(Cutscene.Scene scene, double globalTime) {
        int n = scene.images().size();
        if (n == 1) return scene.images().get(0);
        int f = (int) Math.floor(globalTime * scene.fps()) % n;
        return scene.images().get(Math.max(0, f));
    }

    /**
     * 장면 그림을 화면을 꽉 채우게(cover) 그리고 줌을 건다. override 가 있으면 그 텍스처로 그림.
     * color = 곱할 색 (ARGB). 페이드 전환은 여기 알파로 한다.
     */
    private void drawScene(GuiGraphics g, Cutscene.Scene scene, String imageName, double local,
                           CutsceneTextures.Tex override, int color) {
        CutsceneTextures.Tex tex = override != null ? override : textures.get(imageName);
        // 반복 그림 가운데 아직 안 올라온 장이 있으면 첫 장으로
        if (tex == null) tex = textures.get(scene.images().get(0));
        if (tex == null) return;
        int w = this.width, h = this.height;
        double s = Math.max((double) w / tex.width(), (double) h / tex.height());
        double dw = tex.width() * s, dh = tex.height() * s;
        double dx = (w - dw) / 2, dy = (h - dh) / 2;

        double k = scene.duration() <= 0 ? 1 : easeInOut(Math.min(1, local / scene.duration()));
        double zoom = scene.zoomFrom() + (scene.zoomTo() - scene.zoomFrom()) * k;
        double zx = dx + scene.zoomX() * dw, zy = dy + scene.zoomY() * dh;

        g.pose().pushPose();
        g.pose().translate(zx, zy, 0);
        g.pose().scale((float) zoom, (float) zoom, 1);
        g.pose().translate(-zx, -zy, 0);
        // 반올림 틈이 보이지 않게 1px 크게
        g.blit(RenderType::guiTextured, tex.id(),
                (int) Math.floor(dx), (int) Math.floor(dy), 0, 0,
                (int) Math.ceil(dw) + 1, (int) Math.ceil(dh) + 1,
                tex.width(), tex.height(), tex.width(), tex.height(), color);
        g.pose().popPose();
    }

    private void drawSubtitle(GuiGraphics g, Cutscene.Scene scene, double local, double barFull, int w, int h) {
        Cutscene.Line line = null;
        for (Cutscene.Line l : scene.lines()) {
            if (local >= l.start() && local < l.end()) line = l; // 겹치면 나중 것
        }
        if (line == null) return;
        boolean auto = cutscene.textY() < 0;
        if (auto && barFull < 4) return;

        String body = line.text();
        int total = body.codePointCount(0, body.length());
        int shown = cutscene.typeSpeed() <= 0 ? total
                : Math.min(total, (int) Math.floor((local - line.start()) / cutscene.typeSpeed()) + 1);
        String text = cutscene.prefix() + body.substring(0, body.offsetByCodePoints(0, Math.max(0, shown)));
        typeSoundFor(line, body, shown);

        double alpha = Math.min(1, (local - line.start()) / 0.15);
        alpha = Math.min(alpha, (line.end() - local) / 0.3);
        alpha = Math.max(0, Math.min(1, alpha));
        int a = Math.max(5, (int) Math.round(alpha * 255));

        Style style = Style.EMPTY.withFont(subtitleFont).withItalic(cutscene.italic());
        Component c = Component.literal(text).withStyle(style);
        Font font = this.font;
        // 크기 기준은 레터박스 높이 (레터박스가 0이면 화면 높이의 14%로 계산)
        double base = barFull >= 4 ? barFull : h * 0.14;
        float scale = (float) Math.max(0.3, base * 0.34 * cutscene.fontSize() / font.lineHeight);
        int tw = font.width(c);
        double cx = w * cutscene.textX();
        double cy = auto ? h - barFull / 2.0 : h * cutscene.textY();
        float maxW = (float) (2 * Math.min(cx, w - cx) * 0.95);
        if (maxW > 0 && tw * scale > maxW) scale = maxW / tw;

        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, c, -tw / 2, -font.lineHeight / 2, (a << 24) | (cutscene.textColor() & 0xFFFFFF),
                cutscene.textShadow());
        g.pose().popPose();
    }

    /** 새 글자가 써질 때마다 타자기 소리. 공백은 소리 없음, 너무 촘촘하면 건너뜀. */
    private void typeSoundFor(Cutscene.Line line, String body, int shown) {
        if (line != soundLine) {
            soundLine = line;
            soundShown = 0;
        }
        if (typeSound == null || minecraft == null || shown <= soundShown) return;
        int prev = soundShown;
        soundShown = shown;
        String added = body.substring(body.offsetByCodePoints(0, prev), body.offsetByCodePoints(0, shown));
        if (added.isBlank()) return;
        long now = System.nanoTime();
        if (now - lastTickNanos < 45_000_000L) return;
        lastTickNanos = now;
        if (dialogueTick) {
            // type_sound_volume 0.5 = 대화창과 똑같은 크기
            float volume = (float) (0.07 * cutscene.typeSoundVolume() / 0.5);
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(typeSound, 1.8f, volume));
        } else {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(typeSound, 1.0f, (float) cutscene.typeSoundVolume()));
        }
    }

    /** 배경음 크기: 처음 bgm_fade_in 초 동안 커지고, 끝나기 전 bgm_fade_out 초 동안 줄어듦. */
    private void updateBgm() {
        if (!BgmPlayer.playing()) return;
        double in = cutscene.bgmFadeIn() <= 0 ? 1 : Math.min(1, time / cutscene.bgmFadeIn());
        double left = cutscene.totalDuration() - time;
        double out = cutscene.bgmFadeOut() <= 0 ? 1 : Math.min(1, left / cutscene.bgmFadeOut());
        BgmPlayer.setLevel(smooth(in) * smooth(out));
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private void drawSpeedButton(GuiGraphics g, int mouseX, int mouseY, int w, int bar) {
        Component label = Component.literal(doubleSpeed ? ">> 2x" : ">> 1x");
        int tw = font.width(label);
        int pad = 6;
        speedX1 = w - 10;
        speedX0 = speedX1 - tw - pad * 2;
        speedY0 = Math.max(2, bar / 2 - 8);
        speedY1 = speedY0 + 16;
        boolean hover = mouseX >= speedX0 && mouseX < speedX1 && mouseY >= speedY0 && mouseY < speedY1;
        int border = doubleSpeed ? 0xFFD9A46A : (hover ? 0xFFBBBBBB : 0xFF666666);
        g.fill(speedX0, speedY0, speedX1, speedY1, hover ? 0x40FFFFFF : 0x20FFFFFF);
        g.renderOutline(speedX0, speedY0, speedX1 - speedX0, speedY1 - speedY0, border);
        g.drawString(font, label, speedX0 + pad, speedY0 + 4, doubleSpeed ? 0xFFD9A46A : 0xFFDDDDDD, false);
    }

    private void drawSkipRing(GuiGraphics g, int w, int h, int bar) {
        int cx = w - 18, cy = h - bar / 2;
        int r = 7;
        double progress = Math.min(1, skipHold / SKIP_HOLD_SECONDS);
        int dots = 72;
        for (int i = 0; i < dots; i++) {
            double ang = -Math.PI / 2 + i * 2 * Math.PI / dots;
            int x = (int) Math.round(cx + Math.cos(ang) * r);
            int y = (int) Math.round(cy + Math.sin(ang) * r);
            boolean on = i < progress * dots;
            g.fill(x, y, x + 1, y + 1, on ? 0xFFEFE3C8 : 0x55EFE3C8);
        }
        Component hint = Component.translatable("chaca_story.hold_skip");
        int tw = font.width(hint);
        int a = skipHold > 0 ? 0xDD : 0x77;
        g.pose().pushPose();
        g.pose().translate(cx - r - 6, cy, 0);
        g.pose().scale(0.75f, 0.75f, 1);
        g.drawString(font, hint, -tw, -4, (a << 24) | 0xEFE3C8, false);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- 잉크 전환

    private InkLayer inkFor(int index, Cutscene.Scene scene) {
        if (transitionScene == index) return ink;
        dropInk();
        transitionScene = index;
        CutsceneTextures.Tex src = textures.get(scene.images().get(0));
        if (src == null) return null;
        float[] mask = null;
        if (inkMaskScene == index && inkMask != null) {
            try {
                mask = inkMask.join(); // 보통 이미 끝나 있다
            } catch (RuntimeException e) {
                mask = null;
            }
            inkMask = null;
        }
        if (mask == null || mask.length != src.width() * src.height()) {
            mask = InkMask.upscale(InkMask.build(src.width(), src.height(), scene.zoomX(), scene.zoomY(), index + 1), src.width(), src.height());
        }
        ink = new InkLayer(src, mask);
        return ink;
    }

    /** 전환이 끝나면 전환용 텍스처를 바로 내린다 (장면마다 화면 크기 텍스처가 쌓이지 않게). */
    private void dropInk() {
        if (ink != null && textures != null) textures.release(ink.tex);
        ink = null;
        transitionScene = -1;
    }

    /**
     * 새 장면 그림에 번짐 모양의 알파를 입혀 앞 장면 위로 드러나게 하는 텍스처 한 장.
     * 전환 동안 이 텍스처 하나를 계속 다시 칠해 쓴다 (원본 그림은 복사하지 않고 그대로 읽는다).
     */
    private final class InkLayer {
        final CutsceneTextures.Tex tex;
        final NativeImage source;
        final float[] threshold;
        final int width, height;
        double lastP = -1;
        long lastUpdate = 0;

        InkLayer(CutsceneTextures.Tex source, float[] threshold) {
            this.width = source.width();
            this.height = source.height();
            this.source = source.image();
            this.threshold = threshold;
            this.tex = textures.register(new NativeImage(width, height, true));
            paint(0);
        }

        void update(double p, long nowNanos) {
            if (Math.abs(p - lastP) < 0.002) return;
            // 마지막(완전히 드러남)은 꼭 칠하고, 그 밖에는 너무 자주 칠하지 않는다
            if (p < 0.999 && (nowNanos - lastUpdate) / 1e9 < INK_UPDATE_INTERVAL) return;
            lastUpdate = nowNanos;
            paint(p);
        }

        private void paint(double p) {
            lastP = p;
            NativeImage out = tex.image();
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int px = source.getPixel(x, y);
                    double a = InkMask.alpha(p, threshold[row + x]);
                    int srcA = (px >>> 24) & 0xFF;
                    int na = (int) Math.round(srcA * a);
                    out.setPixel(x, y, (na << 24) | (px & 0x00FFFFFF));
                }
            }
            textures.upload(tex);
        }
    }

    // ---------------------------------------------------------------- 입력과 종료

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (started && button == 0 && mouseX >= speedX0 && mouseX < speedX1 && mouseY >= speedY0 && mouseY < speedY1) {
            doubleSpeed = !doubleSpeed;
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            openGameMenu();
            return true;
        }
        return true; // 그 밖의 키는 무시 (건너뛰기는 스페이스 꾹)
    }

    /**
     * ESC: 컷신을 잠깐 내려 두고 게임 메뉴를 연다. 플레이어가 언제든 설정을 바꾸거나 접속을 끊을 수 있게 하기 위한 것이고
     * 건너뛰기가 아니다. 메뉴(와 거기서 연 화면)가 닫히면 {@link CutscenePlayback} 이 이 화면을 다시 띄운다.
     */
    private void openGameMenu() {
        if (ended || suspended || minecraft == null) return;
        suspended = true;
        skipHold = 0;
        BgmPlayer.pause();
        CutscenePlayback.suspend(this);
        minecraft.setScreen(new PauseScreen(true));
    }

    public boolean isSuspended() {
        return suspended && !ended;
    }

    public boolean isEnded() {
        return ended;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 배경은 render 에서 직접 그림
    }

    /** 텍스처·배경음·읽던 것을 정리한다. bgmSeconds 동안 배경음이 줄어들며 꺼진다. */
    private void cleanup(double bgmSeconds) {
        BgmPlayer.release(bgmSeconds);
        BgmPlayer.holdVanillaMusic(false);
        ink = null;
        if (textures != null) textures.close();
        // 아직 읽는 중이면 다 읽힌 뒤 배경음 메모리를 버린다
        loading.whenComplete((l, e) -> {
            if (l != null) l.discard();
        });
        CutscenePlayback.forget(this);
    }

    /** 서버가 멈추라고 하거나 접속이 끊길 때. 끝난 것으로 치지 않고, 서버에 알리지도 않는다. */
    public void stop() {
        if (ended) return;
        ended = true;
        cleanup(1.5);
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    private void finish(boolean skipped) {
        if (ended) return;
        ended = true;
        cleanup(skipped ? 1.5 : 0.5);
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
        listener.done(new Result(id, skipped, !skipped));
    }

    private void fail(String reason, String detail) {
        if (ended) return;
        ended = true;
        cleanup(0.5);
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
        listener.failed(id, reason, detail);
    }

    @Override
    public void removed() {
        // 게임 메뉴를 여느라 내려간 것은 끝난 게 아니다
        if (ended || suspended) return;
        // 다른 화면이 덮어서 사라짐 (사망 화면, 서버가 연 GUI, 월드 이동 등): 끝난 것이 아니라고 알린다
        ended = true;
        cleanup(1.0);
        listener.aborted(id);
    }

    private static double easeInOut(double t) {
        t = Math.max(0, Math.min(1, t));
        return 0.5 - 0.5 * Math.cos(Math.PI * t);
    }
}
