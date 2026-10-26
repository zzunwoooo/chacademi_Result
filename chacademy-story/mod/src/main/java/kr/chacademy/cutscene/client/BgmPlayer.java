package kr.chacademy.cutscene.client;

import kr.chacademy.cutscene.ChacademyCutsceneClient;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 컷신 배경음. config 폴더의 ogg 를 직접 읽어 OpenAL 로 재생한다
 * (리소스팩 없이 컷신 폴더에 ogg 만 넣으면 되도록).
 *
 * <p>볼륨 = 컷신에서 정한 bgm_volume × 페이드 × 마인크래프트 [주 음량] 설정 ([음악] 설정과는 무관).
 * {@link #decode} 만 아무 스레드에서나 부를 수 있고 (파일 읽기·디코딩은 느려서 작업 스레드에서 한다),
 * 나머지는 모두 클라이언트(렌더) 스레드에서 부른다.
 */
public final class BgmPlayer {
    /** 디코딩이 끝난 소리 (네이티브 메모리). {@link #start(Pcm, double, double)} 에 넘기거나 {@link #free()} 로 버린다. */
    public static final class Pcm {
        private ShortBuffer data;
        private final int channels, rate;

        private Pcm(ShortBuffer data, int channels, int rate) {
            this.data = data;
            this.channels = channels;
            this.rate = rate;
        }

        /** 여러 번 불러도 된다. */
        public synchronized void free() {
            if (data != null) {
                LibCStdlib.free(data);
                data = null;
            }
        }

        private synchronized ShortBuffer take() {
            ShortBuffer d = data;
            data = null;
            return d;
        }
    }

    /** ogg 파일의 길이 한도 (디코딩하면 훨씬 커지므로). */
    private static final long MAX_OGG_BYTES = 32L * 1024 * 1024;

    private static int source = 0;
    private static int buffer = 0;
    /** source / buffer 를 만든 OpenAL 컨텍스트. 소리 엔진이 다시 만들어지면 달라진다. */
    private static long context = 0;
    private static double baseVolume = 0.6;
    /** 컷신이 정해 주는 페이드 값 (0~1). */
    private static double level = 0;
    /** 컷신이 끝난 뒤 스스로 꺼지는 중이면 초당 줄어드는 양. */
    private static double releasePerSecond = 0;
    private static boolean paused = false;
    /** 컷신이 떠 있는 동안 true: 배경음이 없는 컷신이어도 바닐라 배경음악을 멈춰 둔다. */
    private static boolean holdVanilla = false;

    private BgmPlayer() {}

    public static boolean playing() {
        return source != 0;
    }

    /** ogg 파일을 읽어 디코딩한다. 아무 스레드에서나 (OpenAL 을 건드리지 않는다). */
    public static Pcm decode(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_OGG_BYTES) throw new IOException("배경음 파일이 너무 커요 (32MB 까지): " + file.getFileName());
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(data, ch, sr);
                if (pcm == null) throw new IOException("ogg 를 읽지 못했어요: " + file.getFileName());
                int channels = ch.get(0);
                if (channels < 1 || channels > 2) {
                    LibCStdlib.free(pcm);
                    throw new IOException("모노 또는 스테레오 ogg 만 쓸 수 있어요");
                }
                return new Pcm(pcm, channels, sr.get(0));
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** 처음부터 재생 (파일 읽기까지 이 스레드에서). 테스트용 — 컷신은 decode 를 작업 스레드에서 하고 아래 start 를 쓴다. */
    public static void start(Path file, double volume, double startSeconds) throws IOException {
        start(decode(file), volume, startSeconds);
    }

    /** 디코딩된 소리를 재생. 소리는 0에서 시작하고 setLevel 로 올린다. pcm 은 여기서 해제된다. */
    public static void start(Pcm pcm, double volume, double startSeconds) throws IOException {
        stopNow();
        ShortBuffer data = pcm.take();
        if (data == null) throw new IOException("이미 해제된 배경음");
        try {
            int format = pcm.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            context = ALC10.alcGetCurrentContext();
            AL10.alGetError(); // 앞에서 남은 오류 표시를 지운다 (우리 것이 아닌 오류로 실패하지 않게)
            buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, data, pcm.rate);
            source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            // 반복 재생 안 함 (곡이 컷신보다 짧으면 끝난 뒤 조용해짐)
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
            AL10.alSourcef(source, AL10.AL_GAIN, 0);
            if (startSeconds > 0) AL10.alSourcef(source, AL11.AL_SEC_OFFSET, (float) startSeconds);
            AL10.alSourcePlay(source);
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR) {
                abortStart();
                throw new IOException("OpenAL 오류 " + err);
            }
        } catch (RuntimeException | LinkageError e) {
            // 소리 장치가 없을 때 등
            abortStart();
            throw new IOException("배경음을 재생할 수 없어요: " + e, e);
        } finally {
            LibCStdlib.free(data);
        }
        baseVolume = volume;
        level = 0;
        releasePerSecond = 0;
        paused = false;
    }

    /** 컷신 화면이 떠 있는 동안 켜 둔다: 바닐라 배경음악이 겹치지 않게 계속 멈춘다 (배경음이 없는 컷신도). */
    public static void holdVanillaMusic(boolean hold) {
        holdVanilla = hold;
        if (hold) stopVanillaMusic();
    }

    private static void stopVanillaMusic() {
        try {
            Minecraft.getInstance().getMusicManager().stopPlaying();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 소리 엔진이 다시 만들어지면 (리소스 다시 불러오기, 소리 장치 변경) 예전 OpenAL 번호는 없어지거나
     * 마인크래프트의 다른 소리를 가리킨다. 그런 번호를 쓰지 않도록 쓰기 전에 확인하고
     * (같은 컨텍스트인지, 아직 우리 버퍼가 물려 있는지), 아니면 지우지 않고 그냥 잊는다.
     */
    private static boolean alive() {
        if (source == 0) return false;
        try {
            if (ALC10.alcGetCurrentContext() == context && AL10.alIsSource(source)
                    && AL10.alGetSourcei(source, AL10.AL_BUFFER) == buffer) return true;
        } catch (RuntimeException | LinkageError ignored) {
        }
        forget();
        return false;
    }

    private static void forget() {
        source = 0;
        buffer = 0;
        context = 0;
        level = 0;
        releasePerSecond = 0;
        paused = false;
    }

    /** 컷신이 매 프레임 알려 주는 페이드 값. */
    public static void setLevel(double value) {
        if (source == 0 || releasePerSecond > 0 || paused) return;
        level = Math.max(0, Math.min(1, value));
        applyGain();
    }

    /** 게임 메뉴(ESC)가 열려 컷신이 멈춘 동안. */
    public static void pause() {
        if (paused || !alive()) return;
        try {
            AL10.alSourcePause(source);
            paused = true;
        } catch (RuntimeException | LinkageError e) {
            ChacademyCutsceneClient.LOGGER.warn("배경음 일시정지 실패", e);
        }
    }

    public static void resume() {
        if (!paused) return;
        paused = false;
        if (!alive()) return;
        try {
            AL10.alSourcePlay(source);
        } catch (RuntimeException | LinkageError e) {
            ChacademyCutsceneClient.LOGGER.warn("배경음 다시 재생 실패", e);
        }
    }

    /** 컷신이 끝나거나 건너뛰었을 때. 지금 크기에서 seconds 동안 줄어들다 꺼진다. */
    public static void release(double seconds) {
        if (source == 0) return;
        if (paused || level <= 0.001 || seconds <= 0) {
            stopNow();
            return;
        }
        releasePerSecond = level / seconds;
    }

    /** 매 틱 (1/20초) 호출. 끝난 뒤 페이드와 설정 볼륨 반영. */
    public static void tick() {
        // 컷신 도중 바닐라 배경음악이 다시 시작되지 않게
        if (holdVanilla || (source != 0 && releasePerSecond <= 0)) stopVanillaMusic();
        if (source == 0 || !alive() || paused) return;
        if (releasePerSecond > 0) {
            level -= releasePerSecond / 20.0;
            if (level <= 0) {
                stopNow();
                return;
            }
        }
        applyGain();
    }

    private static void applyGain() {
        if (!alive()) return;
        try {
            var options = Minecraft.getInstance().options;
            // [음악]은 바닐라 배경음악 때문에 꺼 두는 사람이 많아서 [주 음량]만 따른다
            double settings = options.getSoundSourceVolume(SoundSource.MASTER);
            AL10.alSourcef(source, AL10.AL_GAIN, (float) (baseVolume * level * settings));
        } catch (RuntimeException | LinkageError e) {
            ChacademyCutsceneClient.LOGGER.warn("배경음 크기 조절 실패", e);
        }
    }

    public static void stopNow() {
        try {
            if (source != 0 && alive()) {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
                if (buffer != 0) AL10.alDeleteBuffers(buffer);
            }
        } catch (Throwable t) {
            ChacademyCutsceneClient.LOGGER.warn("배경음 정리 실패", t);
        }
        forget();
    }

    /** start 도중 실패: 방금 만든 것만 지운다 (아직 버퍼가 물리지 않았을 수 있어 alive() 로 확인할 수 없다). */
    private static void abortStart() {
        try {
            if (source != 0) AL10.alDeleteSources(source);
            if (buffer != 0) AL10.alDeleteBuffers(buffer);
            AL10.alGetError();
        } catch (Throwable ignored) {
        }
        forget();
    }
}
