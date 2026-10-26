package school.magiccodex.paper;

import java.util.*;
import school.magiccodex.protocol.PermissionProtocol;
import school.magiccodex.protocol.PermissionProtocol.Request;
import school.magiccodex.protocol.PermissionProtocol.Response;

/** Main-thread engine. Work is bounded and only active codex viewers are retained. */
public final class PermissionSubscriptions {
    public interface Access {
        boolean online(UUID player);
        boolean hasPermission(UUID player, String permission);
        void send(UUID player, Response response);
    }
    private static final class Session {
        final Request request;
        long lastSeen, lastCheck;
        boolean dirty = true;
        List<Boolean> previous;
        Session(Request request, long now) { this.request = request; lastSeen = now; }
    }
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpens = new HashMap<>();
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final long refreshMillis, timeoutMillis;
    private final int budget;
    private int remainingBudget;
    private long budgetWindowStart = Long.MIN_VALUE;
    public PermissionSubscriptions(long refreshMillis, long timeoutMillis, int budget) {
        if (refreshMillis < 1000 || timeoutMillis < 30000 || budget < PermissionProtocol.MAX_PERMISSIONS)
            throw new IllegalArgumentException("Invalid subscription limits");
        this.refreshMillis = refreshMillis; this.timeoutMillis = timeoutMillis; this.budget = budget;
    }
    /** Called before decoding OPEN packets to bound both parsing and permission work. */
    public boolean allowOpen(UUID player, long now) {
        Long previous = lastOpens.get(player);
        if (previous != null && now - previous < 1000) return false;
        lastOpens.put(player, now);
        return true;
    }
    public void accept(UUID player, Request request, long now) {
        Session old = sessions.get(player);
        if (request.action() == PermissionProtocol.OPEN) {
            if (old == null) queue.addLast(player);
            sessions.put(player, new Session(request, now));
        } else if (old != null && old.request.id() == request.id()) {
            if (request.action() == PermissionProtocol.CLOSE) unsubscribe(player);
            else old.lastSeen = now;
        }
    }
    public boolean subscribed(UUID player) { return sessions.containsKey(player); }
    public void dirty(UUID player) { var session = sessions.get(player); if (session != null) session.dirty = true; }
    private void unsubscribe(UUID player) { sessions.remove(player); queue.remove(player); }
    public void remove(UUID player) { unsubscribe(player); lastOpens.remove(player); }
    public void clear() { sessions.clear(); lastOpens.clear(); queue.clear(); budgetWindowStart = Long.MIN_VALUE; remainingBudget = 0; }
    public int size() { return sessions.size(); }
    public void process(long now, Access access) {
        // Immediate OPEN handling and the maintenance timer share a single one-second budget.
        if (budgetWindowStart == Long.MIN_VALUE || now - budgetWindowStart >= 1000) {
            budgetWindowStart = now;
            remainingBudget = budget;
        }
        if (remainingBudget == 0) return;
        int turns = queue.size();
        for (int turn = 0; turn < turns; turn++) {
            UUID player = queue.removeFirst();
            Session session = sessions.get(player);
            if (!access.online(player) || now - session.lastSeen > timeoutMillis) {
                sessions.remove(player);
                if (!access.online(player)) lastOpens.remove(player);
                continue;
            }
            if ((session.dirty || now - session.lastCheck >= refreshMillis)
                    && session.request.permissions().size() <= remainingBudget) {
                remainingBudget -= session.request.permissions().size();
                List<Boolean> result = session.request.permissions().stream().map(p -> access.hasPermission(player, p)).toList();
                boolean changed = !result.equals(session.previous);
                session.previous = result;
                session.dirty = false;
                session.lastCheck = now;
                // Update bookkeeping before sending: a synchronous permission event cannot cause a loop.
                if (changed) access.send(player, new Response(session.request.id(), result));
            }
            queue.addLast(player);
            // Keep unprocessed viewers at the front for the next pass, even under a steady burst.
            if (remainingBudget == 0) break;
        }
    }
}
