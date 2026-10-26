package kr.chacademy.motion.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 → 클라 패킷 디코더 보호 (2026-10-21). 플러그인과 모드 버전이 어긋나 필드가 모자라거나 남거나 값이 잘못돼도
 * 넷티 디코드 예외로 접속이 끊기지 않게 한다. 깨진 패킷은 남은 바이트를 모두 버리고 {@code invalid} 를 돌려주며
 * (핸들러가 무시), 뒤에 붙은 모르는 필드는 버린다. 종류마다 콘솔 경고는 한 번만 남긴다. 바이트 형식은 그대로다.
 */
public final class SafeCodec {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SafeCodec() {}

    public static <T> StreamCodec<RegistryFriendlyByteBuf, T> failSafe(String name, StreamCodec<RegistryFriendlyByteBuf, T> inner, T invalid) {
        return StreamCodec.of(
                (buf, value) -> inner.encode(buf, value),
                buf -> {
                    T value;
                    try {
                        value = inner.decode(buf);
                    } catch (RuntimeException error) {
                        buf.skipBytes(buf.readableBytes());
                        warnOnce(name, "깨진 패킷을 무시함 (서버 플러그인과 모드 버전 확인): " + error);
                        return invalid;
                    }
                    if (buf.readableBytes() > 0) {
                        buf.skipBytes(buf.readableBytes());
                        warnOnce(name + "#tail", "모르는 뒷부분을 무시함 (서버 플러그인이 더 새 버전일 수 있음)");
                    }
                    return value;
                });
    }

    static void warnOnce(String name, String message) {
        if (WARNED.add(name)) kr.chacademy.motion.ChacademyMotionClient.LOGGER.warn("[{}] {}", name, message);
    }
}
