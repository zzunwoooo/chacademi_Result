package kr.chacademy.motion.broom.trail;

import kr.chacademy.motion.ChacademyMotionClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PortableVFX 클라이언트 API 를 리플렉션으로 부른다 (컴파일 의존 없음).
 *
 * <p>사용하는 계약 (PortableVFX client {@code dev.portablevfx.client.api.PortableVfxApi}, 2026-10-15 소스 기준):
 * <ul>
 *   <li>{@code PortableVfxClient.api()} — 정적, 초기화 전이면 IllegalStateException</li>
 *   <li>{@code play(Identifier, Vec3d, float scale, int durationTicks)} → {@code Optional<UUID>} 인스턴스 핸들 (WORLD 앵커).
 *       durationTicks 0 = 팩 기본값(loop 시스템은 12000틱 = 10분)</li>
 *   <li>{@code move(UUID, Vec3d)}, {@code basis(UUID, Vec3d right, Vec3d up, Vec3d forward)}, {@code finish(UUID)},
 *       {@code stop(UUID)} → boolean. 모두 인스턴스 UUID 로 제어하므로 같은 효과 ID 를 여러 명이 써도 섞이지 않는다</li>
 *   <li>{@code activeInstances()} → {@code Set<UUID>}, {@code list()} → {@code Set<Identifier>}</li>
 * </ul>
 * Identifier/Vec3d 는 실행 시 ResourceLocation/Vec3 와 같은 클래스(intermediary)라 그대로 넘긴다.
 * 메서드는 처음 한 번만 찾아 캐시하고, 계약이 안 맞으면 궤적 기능만 끄고 로그는 한 번만 남긴다.
 * 모든 호출은 클라이언트 스레드에서.
 */
final class VfxBridge {
    private static final String MOD_ID = "portablevfx";

    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object api;
    private static Method play, move, basis, finish, stop, active, list;

    private VfxBridge() {}

    /** PortableVFX 가 있고 계약이 맞으면 true. 처음 부를 때만 탐색한다. */
    static boolean ready() {
        if (state == State.UNRESOLVED) resolve();
        return state == State.READY;
    }

    private static void resolve() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            disable("PortableVFX 모드 없음 → 빗자루 궤적 끔", null);
            return;
        }
        try {
            Class<?> client = Class.forName("dev.portablevfx.client.PortableVfxClient");
            Object a;
            try {
                a = client.getMethod("api").invoke(null);
            } catch (InvocationTargetException notYet) {
                // 아직 초기화 전: 다음 틱에 다시 (UNRESOLVED 유지, 로그 없음)
                return;
            }
            Class<?> c = a.getClass();
            Method p = null;
            for (Method m : c.getMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (m.getName().equals("play") && t.length == 4 && t[2] == float.class && t[3] == int.class
                        && Optional.class.isAssignableFrom(m.getReturnType())) p = m;
            }
            Method mv = find(c, "move", 2), bs = find(c, "basis", 4), fn = find(c, "finish", 1), st = find(c, "stop", 1);
            Method ac = c.getMethod("activeInstances"), ls = c.getMethod("list");
            if (p == null || mv == null || bs == null || fn == null || st == null
                    || mv.getParameterTypes()[0] != UUID.class || fn.getParameterTypes()[0] != UUID.class
                    || st.getParameterTypes()[0] != UUID.class || bs.getParameterTypes()[0] != UUID.class) {
                disable("PortableVFX API 형식이 예상과 다름 → 빗자루 궤적 끔", null);
                return;
            }
            api = a; play = p; move = mv; basis = bs; finish = fn; stop = st; active = ac; list = ls;
            state = State.READY;
            ChacademyMotionClient.LOGGER.info("빗자루 궤적: PortableVFX 연결됨");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable("PortableVFX API 연결 실패 → 빗자루 궤적 끔", e);
        }
    }

    private static Method find(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && m.getParameterTypes()[0] == UUID.class) return m;
        }
        return null;
    }

    private static void disable(String why, Throwable e) {
        state = State.UNAVAILABLE;
        api = null;
        if (e == null) ChacademyMotionClient.LOGGER.info(why);
        else ChacademyMotionClient.LOGGER.warn("{} ({})", why, e.toString());
    }

    /** 호출 중 예외 = 계약 위반으로 보고 기능을 끈다 (한 번만 로그). */
    private static Object call(Method m, Object... args) {
        try {
            return m.invoke(api, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            // 스레드 위반·잘못된 인자 등은 우리 쪽 버그이므로 기능을 끄고 알린다
            disable("PortableVFX 호출 오류 → 빗자루 궤적 끔", cause == null ? e : cause);
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("PortableVFX 호출 실패 → 빗자루 궤적 끔", e);
            return null;
        }
    }

    /** @return 인스턴스 UUID, 거절되면 null (한도·없는 ID·월드 불일치·준비 안 됨) */
    static UUID play(ResourceLocation effect, Vec3 pos) {
        if (!ready()) return null;
        Object r = call(play, effect, pos, 1f, 0);
        if (r instanceof Optional<?> o && o.isPresent() && o.get() instanceof UUID id) return id;
        return null;
    }

    static boolean move(UUID id, Vec3 pos) {
        return ready() && Boolean.TRUE.equals(call(move, id, pos));
    }

    static boolean basis(UUID id, Vec3 right, Vec3 up, Vec3 forward) {
        return ready() && Boolean.TRUE.equals(call(basis, id, right, up, forward));
    }

    /** 방출만 멈춤: 남은 입자는 onStop 꼬리만큼 보이고 사라진다. */
    static void finish(UUID id) {
        if (ready()) call(finish, id);
    }

    /** 즉시 제거 (순간이동·효과 교체용). */
    static void stop(UUID id) {
        if (ready()) call(stop, id);
    }

    @SuppressWarnings("unchecked")
    static Set<UUID> activeInstances() {
        if (!ready()) return Set.of();
        Object r = call(active);
        return r instanceof Set<?> s ? (Set<UUID>) s : Set.of();
    }

    /** 불러온 효과 ID (문자열). PortableVFX 가 아직 불러오기 전이면 빈 집합. */
    static Set<String> loadedIds() {
        if (!ready()) return Set.of();
        Object r = call(list);
        if (!(r instanceof Set<?> s)) return Set.of();
        var out = new java.util.HashSet<String>();
        for (Object o : s) out.add(String.valueOf(o));
        return out;
    }

}
