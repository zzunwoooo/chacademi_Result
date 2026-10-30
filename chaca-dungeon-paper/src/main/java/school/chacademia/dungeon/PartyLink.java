package school.chacademia.dungeon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

// [기능:파티던전 시작]
/**
 * MagicCodexBridge 의 파티 명단(PartyRoster)·우편(MailService)을 리플렉션으로 부른다 (배포 jar 가 소스와 달라도 이름만 맞으면 됨).
 * 파티는 Velocity 가 정하고 이 서버로 5초마다 밀어 준다 → 여기서는 읽기만.
 */
final class PartyLink {
    private PartyLink() {}

    /** 파티 하나: leader = 파티장, members = 파티장 포함 전원 (순서 = 프록시가 보낸 순서). */
    record Party(UUID leader, List<UUID> members) {}

    private static Object service(String className) {
        for (Class<?> c : Bukkit.getServicesManager().getKnownServices())
            if (c.getName().equals(className)) {
                var reg = Bukkit.getServicesManager().getRegistration(c);
                return reg == null ? null : reg.getProvider();
            }
        return null;
    }

    /** 파티 명단 연결이 있는지 (없으면 아무도 파티가 아님). */
    static boolean ready() { return service("school.magiccodex.paper.PartyRoster") != null; }

    /** 이 사람의 파티 (혼자면 empty). */
    @SuppressWarnings("unchecked")
    static Optional<Party> party(UUID player) {
        Object roster = service("school.magiccodex.paper.PartyRoster");
        if (roster == null) return Optional.empty();
        try {
            Method m = roster.getClass().getMethod("party", UUID.class);
            var opt = (Optional<Object>) m.invoke(roster, player);
            if (opt.isEmpty()) return Optional.empty();
            Object p = opt.get();
            UUID leader = (UUID) p.getClass().getMethod("leader").invoke(p);
            var members = (List<UUID>) p.getClass().getMethod("members").invoke(p);
            if (members == null || members.size() < 2 || !members.contains(player)) return Optional.empty();
            return Optional.of(new Party(leader, List.copyOf(members)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * 시스템 우편 (가방 대신 우편함으로 바로). sourceKey = 이 지급의 고유 번호 (같은 번호는 한 번만 보내짐).
     * 우편이 없으면 실패한 future (부르는 쪽이 다른 길로 준다).
     */
    @SuppressWarnings("unchecked")
    static CompletableFuture<UUID> mail(String sourceKey, UUID to, String title, String body, List<ItemStack> items) {
        Object mail = service("school.magiccodex.paper.MailService");
        if (mail == null) return CompletableFuture.failedFuture(new IllegalStateException("우편 서비스 없음"));
        try {
            Method m = mail.getClass().getMethod("sendSystem", String.class, UUID.class, String.class, String.class, List.class);
            m.setAccessible(true);
            return (CompletableFuture<UUID>) m.invoke(mail, sourceKey, to, title, body, items);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
// [기능:파티던전 끝]
