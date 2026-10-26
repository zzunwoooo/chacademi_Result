package kr.chacademy.storyplugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * MagicCodexBridge / PlaceholderAPI 와 연결. 둘 다 컴파일 때 필요 없도록 리플렉션으로 부른다.
 * <ul>
 *   <li>한글 닉네임: NpcSocialFacade.playerName(Player) → 없으면 %user_nickname% → 없으면 마인크래프트 닉네임</li>
 *   <li>호감도: NpcSocialFacade.affinity / addAffinity. ChacaNPC 도 같은 곳을 쓰므로 AI NPC 와 스토리가 같은 점수를 본다</li>
 * </ul>
 * facade 는 메인 스레드에서만 부르고, future 도 메인 스레드에서 끝난다 (MagicCodex 약속).
 * <p>MagicCodexBridge 가 꺼지거나 다시 켜지면 (서비스 등록 해제, 호출 실패) 연결을 버리고
 * {@value #RECONNECT_MILLIS}ms 에 한 번씩 다시 찾는다. 연결이 없는 동안 스토리 호감도 변경은 적용되지 않고 콘솔에 남는다 (호감도는 MagicCodex 하나로만).
 */
public final class MagicCodexLink {
    public static final String FACADE = "school.magiccodex.paper.NpcSocialFacade";
    /** MagicCodex affinity.yml 의 출처 이름. 기본 하루 한도 0(없음). */
    public static final String SOURCE = "dialogue";

    private final Logger log;
    private Object facade;
    private final Map<String, Method> methods = new HashMap<>();
    private Method papi;
    private boolean papiLooked;
    static final long RECONNECT_MILLIS = 10_000;
    /** 한 번이라도 연결됐었는지 (끊긴 뒤 다시 찾을지 결정). */
    private boolean wanted;
    private long lastReconnect;
    private long lastDropLog;
    private long dropped;

    public MagicCodexLink(Logger log) {
        this.log = log;
    }

    /** 서비스가 등록 해제됐거나 호출이 실패했을 때: 낡은 연결을 버린다. 다음 호출에서 다시 찾는다. */
    public void invalidate() {
        if (facade != null) log.warning("MagicCodexBridge 연결이 끊겼습니다. 다시 연결될 때까지 스토리 호감도는 적용되지 않고, 닉네임은 PlaceholderAPI 를 씁니다.");
        facade = null;
        methods.clear();
        lastReconnect = 0;
    }

    /** 끊긴 연결을 가끔 다시 찾는다 (메인 스레드). */
    private void reconnectIfDue() {
        if (facade != null || !wanted) return;
        long now = System.currentTimeMillis();
        if (now - lastReconnect < RECONNECT_MILLIS) return;
        lastReconnect = now;
        try {
            connect();
        } catch (RuntimeException | LinkageError e) {
            log.warning("MagicCodexBridge 다시 연결 실패: " + e);
        }
    }

    /** 호감도 변경이 MagicCodex 에서 실패해 버려졌을 때 (너무 자주 찍지 않는다). */
    public void affinityDropped(String who, String npc, int amount) {
        dropped++;
        long now = System.currentTimeMillis();
        if (now - lastDropLog < 60_000) return;
        lastDropLog = now;
        log.warning("MagicCodex 호감도 변경이 적용되지 않았습니다: " + who + " → " + npc + " " + (amount > 0 ? "+" : "") + amount
                + " (지금까지 " + dropped + "건). MagicCodexBridge 상태와 DB 를 확인하세요.");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean connect() {
        facade = null;
        methods.clear();
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!type.getName().equals(FACADE)) continue;
            RegisteredServiceProvider reg = Bukkit.getServicesManager().getRegistration((Class) type);
            if (reg == null) continue;
            try {
                if (!bind(type, reg.getProvider())) {
                    log.warning("MagicCodexBridge NPC API 버전이 맞지 않아 연결하지 않습니다 (스토리 호감도 적용 안 됨).");
                    return false;
                }
                log.info("MagicCodexBridge 연결 완료 (한글 닉네임, 호감도를 ChacaNPC 와 같이 씀)");
                wanted = true;
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.warning("MagicCodexBridge 연결 실패: " + e);
                methods.clear();
            }
        }
        return false;
    }

    /** Exact API-1 signatures, independently testable without a running server. */
    boolean bind(Class<?> type, Object provider) throws ReflectiveOperationException {
        facade = null; methods.clear();
        Method version = type.getMethod("apiVersion");
        if (((Number)version.invoke(provider)).intValue() != 1) return false;
        methods.put("playerName", type.getMethod("playerName", Player.class));
        methods.put("affinity", type.getMethod("affinity", UUID.class, String.class));
        methods.put("addAffinity", type.getMethod("addAffinity", UUID.class, String.class, String.class, int.class));
        try { methods.put("resolvePlayer",type.getMethod("resolvePlayer",String.class)); }
        catch(NoSuchMethodException ignored) { } // Older Bridge keeps account-name compatibility.
        try { methods.put("questNpcTargets", type.getMethod("questNpcTargets", Player.class)); }
        catch (NoSuchMethodException ignored) { } // 옛 Bridge: 길잡이 별이 의뢰 NPC 를 가리키지 않을 뿐 (#93)
        facade = provider;
        return true;
    }

    public boolean available() {
        reconnectIfDue();
        return facade != null;
    }

    private Object call(String name, Object... args) throws ReflectiveOperationException {
        Method m = methods.get(name);
        if (facade == null || m == null) throw new NoSuchMethodException(name);
        try {
            return m.invoke(facade, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 꺼진 플러그인의 낡은 객체일 수 있다: 버리고 다시 찾는다
            log.warning("MagicCodexBridge 호출 실패 (" + name + "): " + e);
            invalidate();
            if (e instanceof ReflectiveOperationException r) throw r;
            if (e instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 이름

    public Player resolvePlayer(String input) {
        if(facade==null||!methods.containsKey("resolvePlayer"))return Bukkit.getPlayerExact(input);
        try {return (Player)call("resolvePlayer",input);}
        catch(ReflectiveOperationException|RuntimeException e){return null;}
    }

    /** MagicCodex 에서 정한 한글 닉네임 (칭호 없이). */
    public String nickname(Player p) {
        if (facade != null) {
            try {
                Object v = call("playerName", p);
                if (v instanceof String s && !s.isBlank()) return s;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        String v = placeholder(p, "%user_nickname%");
        if (v != null && !v.isBlank() && !v.contains("%")) return v;
        return p.getName();
    }

    /** PlaceholderAPI 로 계산. PlaceholderAPI 가 없으면 null. */
    public String placeholder(OfflinePlayer p, String text) {
        if (!papiLooked) {
            papiLooked = true;
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                                    Bukkit.getPluginManager().getPlugin("PlaceholderAPI").getClass().getClassLoader())
                            .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    log.warning("PlaceholderAPI 연결 실패: " + e);
                }
            }
        }
        if (papi == null) return null;
        try {
            Object v = papi.invoke(null, p, text);
            return v instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public void resetPlaceholderLookup() {
        papiLooked = false;
        papi = null;
    }

    // ---------------------------------------------------------------- 의뢰 NPC 목표 (길잡이 별, 2026-10-26 메모 #93)

    /** 진행 중 의뢰에서 아직 못 채운 "NPC" 목표의 Citizens 번호 (이 서버 것만, MagicCodex 캐시만 봄). 연결이 없으면 빈 배열. 메인 스레드. */
    public int[] questNpcTargets(Player p) {
        if (!available() || !methods.containsKey("questNpcTargets")) return new int[0];
        try {
            Object v = call("questNpcTargets", p);
            return v instanceof int[] a ? a : new int[0];
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new int[0];
        }
    }

    // ---------------------------------------------------------------- 호감도

    /** 점수. 실패하면 null 로 끝남. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Integer> affinity(UUID player, String npc) {
        try {
            return ((CompletableFuture<int[]>) call("affinity", player, npc)).handle((r, e) -> e != null || r == null || r.length == 0 ? null : r[0]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 바꾼 뒤 점수. 실패하면 null. MagicCodex 의 하트 단계 상한·하루 한도가 그대로 적용된다. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Integer> addAffinity(UUID player, String npc, int amount) {
        try {
            return ((CompletableFuture<int[]>) call("addAffinity", player, npc, SOURCE, amount))
                    .handle((r, e) -> e != null || r == null || r.length == 0 ? null : r[0]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
