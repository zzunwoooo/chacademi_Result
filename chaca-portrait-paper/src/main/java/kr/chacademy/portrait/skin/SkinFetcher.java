package kr.chacademy.portrait.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 플레이어에게 지금 적용된 스킨 (Paper 프로필의 textures 속성 — SkinsRestorer 등으로 바뀐 스킨도 여기에 반영됨).
 * 프로필 읽기는 메인 스레드, 다운로드는 작업 스레드에서.
 */
public final class SkinFetcher {

    /** 스킨 위치 + 가는 팔 여부. url이 null이면 스킨 없음. */
    public record SkinRef(String url, boolean slim) {
        /** 스킨이 바뀌었는지 비교용 (텍스처 해시). */
        public String hash() {
            if (url == null) {
                return "";
            }
            int i = url.lastIndexOf('/');
            return i < 0 ? url : url.substring(i + 1);
        }
    }

    private static final int MAX_BYTES = 256 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 메인 스레드에서 호출. */
    public static SkinRef refOf(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        PlayerTextures textures = profile.getTextures();
        URL skin = textures.getSkin();
        if (skin == null) {
            return new SkinRef(null, false);
        }
        return new SkinRef(skin.toString(), textures.getSkinModel() == PlayerTextures.SkinModel.SLIM);
    }

    /** 작업 스레드에서 호출. textures.minecraft.net 만 허용. */
    public byte[] download(SkinRef ref) throws IOException, InterruptedException {
        if (ref.url() == null) {
            throw new IOException("스킨 없음");
        }
        URI uri = URI.create(ref.url());
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase("textures.minecraft.net")) {
            throw new IOException("허용되지 않은 스킨 주소");
        }
        URI https = URI.create("https://textures.minecraft.net" + uri.getRawPath());
        HttpRequest req = HttpRequest.newBuilder(https).timeout(Duration.ofSeconds(12)).GET().build();
        // HttpRequest.timeout 은 응답 헤더까지만 본다 → 본문 수신까지 포함해 전체 20초 제한 (넘으면 취소).
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> f =
                http.sendAsync(req, kr.chacademy.portrait.ai.LimitedResponseBody.bytes(MAX_BYTES));
        HttpResponse<byte[]> res;
        try {
            res = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new IOException("스킨 다운로드 시간 초과");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw new IOException("스킨 다운로드 실패: " + (c == null ? e : c).getClass().getSimpleName());
        } catch (InterruptedException e) {
            f.cancel(true);
            throw e;
        }
        if (res.statusCode() != 200) {
            throw new IOException("스킨 다운로드 실패 " + res.statusCode());
        }
        return res.body();
    }

    /** 플러그인 종료 시: 연결·내부 스레드 정리. */
    public void close() {
        try {
            http.shutdownNow();
        } catch (RuntimeException ignored) {
            // 무시
        }
    }
}
