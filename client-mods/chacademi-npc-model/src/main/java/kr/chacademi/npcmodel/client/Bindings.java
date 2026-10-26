package kr.chacademi.npcmodel.client;

import kr.chacademi.npcmodel.NpcModelClient;
import kr.chacademi.npcmodel.protocol.NpcModelProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 서버가 알려 준 "엔티티 → 모델" 연결. 렌더 스레드(클라이언트 메인 스레드)에서만 다룬다.
 *
 * <p>엔티티 번호는 재사용될 수 있어서 항상 UUID 까지 맞아야 쓰고, 같은 UUID 에서는 revision 이 낮은 패킷을 버린다.
 * 서버 세션 번호가 바뀌면(서버 재시작·서버 이동) 전부 버린다. 패킷이 엔티티보다 먼저 와도 잠시 보관했다가 붙인다.</p>
 */
public final class Bindings {
    private static final int PENDING_TTL_TICKS = 20 * 30;

    private final ModelManager models;
    private final Map<Integer, Entry> byEntity = new HashMap<>();
    private long session;
    private boolean sessionKnown;
    private int ticks;
    private int rejectedPackets;

    public Bindings(ModelManager models) {
        this.models = models;
    }

    static final class Entry {
        final int entityId;
        final UUID uuid;
        int revision;
        String modelId;
        float scale;
        float yaw = Float.NaN; // 서버 고정 방향 (NaN = 엔티티 몸 방향)
        int base, greetSeq;
        NpcInstance instance;
        int missingSince = -1;

        Entry(int entityId, UUID uuid) {
            this.entityId = entityId;
            this.uuid = uuid;
        }
    }

    /** 새 세션(처음·서버 재시작·서버 이동)이면 true → 그때만 HELLO 를 보낸다. */
    public boolean onSession(long s) {
        if (!sessionKnown || s != session) {
            clear();
            session = s;
            sessionKnown = true;
            return true;
        }
        return false;
    }

    /** 접속 끊김: 다음 서버의 세션을 새로 받게. */
    public void resetSession() {
        clear();
        sessionKnown = false;
    }

    /** 이 모델을 그리다 예외가 남: 그 모델을 실패로 표시하고 연결된 인스턴스를 내린다 (→ 원래 외형). */
    public void failModel(String modelId, Throwable t) {
        models.markFailed(modelId, t);
        for (Entry e : byEntity.values()) {
            if (modelId.equals(e.modelId)) {
                release(e);
            }
        }
    }

    /** 연결은 두고 인스턴스만 전부 내린다 (models.json 다시 읽기). */
    public void clearInstances() {
        for (Entry e : byEntity.values()) {
            release(e);
        }
    }

    /** 조절 명령으로 모델을 다시 읽을 때: 그 모델을 쓰는 인스턴스만 내린다 (다음 프레임에 새로 만듦). */
    public void reloadModel(String modelId) {
        for (Entry e : byEntity.values()) {
            if (modelId.equals(e.modelId)) {
                release(e);
            }
        }
    }

    public String modelIdOf(NpcInstance inst) {
        return inst.model().entry.id();
    }

    public void onMessage(NpcModelProtocol.Message m) {
        if (!sessionKnown) {
            return; // HELLO_ACK 전에 온 것은 무시 (서버가 HELLO 뒤 다시 보낸다)
        }
        switch (m) {
            case NpcModelProtocol.Appear a -> {
                Entry e = byEntity.get(a.entityId());
                if (e != null && e.uuid.equals(a.uuid()) && a.revision() < e.revision) {
                    return;
                }
                if (e == null || !e.uuid.equals(a.uuid())) {
                    release(e);
                    e = new Entry(a.entityId(), a.uuid());
                    byEntity.put(a.entityId(), e);
                } else if (!a.modelId().equals(e.modelId)) {
                    release(e);
                }
                e.revision = a.revision();
                e.modelId = a.modelId();
                e.scale = a.scale();
                e.yaw = a.yaw();
                e.base = a.base();
                e.greetSeq = a.greetSeq();
                if (e.instance != null) {
                    e.instance.setScale(a.scale());
                    e.instance.setFixedYaw(a.yaw());
                    e.instance.setServerState(a.base(), a.greetSeq());
                }
                models.request(a.modelId()); // 미리 읽기 시작 (비동기)
            }
            case NpcModelProtocol.State s -> {
                Entry e = byEntity.get(s.entityId());
                if (e == null || !e.uuid.equals(s.uuid()) || s.revision() != e.revision) {
                    return;
                }
                e.base = s.base();
                e.greetSeq = s.greetSeq();
                if (e.instance != null) {
                    e.instance.setServerState(s.base(), s.greetSeq());
                }
            }
            case NpcModelProtocol.Clear c -> {
                Entry e = byEntity.get(c.entityId());
                if (e != null && e.uuid.equals(c.uuid()) && c.revision() >= e.revision) {
                    release(e);
                    byEntity.remove(c.entityId());
                }
            }
            case NpcModelProtocol.Reset r -> clear();
            default -> {
            }
        }
    }

    public void onEntityUnload(Entity entity) {
        Entry e = byEntity.get(entity.getId());
        if (e != null && e.uuid.equals(entity.getUUID())) {
            release(e);
            byEntity.remove(entity.getId());
        }
    }

    /** 이 엔티티를 지금 3D 모델로 그릴 수 있으면 인스턴스, 아니면 null (→ 원래 외형). */
    public NpcInstance instanceFor(Entity entity) {
        Entry e = byEntity.get(entity.getId());
        if (e == null || !e.uuid.equals(entity.getUUID())) {
            return null;
        }
        if (e.instance == null) {
            ModelManager.LoadedModel m = models.ready(e.modelId);
            if (m == null) {
                return null; // 읽는 중·실패·없음
            }
            e.instance = new NpcInstance(m, entity.getId());
            e.instance.setScale(e.scale);
            e.instance.setFixedYaw(e.yaw);
            e.instance.setServerState(e.base, e.greetSeq);
        } else if (!e.instance.model().alive()) {
            release(e); // 모델이 내려감(새로고침) → 다음 프레임에 다시
            return null;
        }
        return e.instance;
    }

    public double topHeight(Entity entity) {
        Entry e = byEntity.get(entity.getId());
        if (e == null || e.instance == null || !e.uuid.equals(entity.getUUID())) {
            return -1;
        }
        return e.instance.topHeight();
    }

    public void tick(Minecraft mc) {
        if (++ticks % 100 != 0 || mc.level == null) {
            return;
        }
        // 엔티티가 끝내 안 나타난 연결은 정리 (Citizens 재생성 등으로 번호가 바뀐 경우)
        for (Iterator<Entry> it = byEntity.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            Entity ent = mc.level.getEntity(e.entityId);
            if (ent != null && ent.getUUID().equals(e.uuid)) {
                e.missingSince = -1;
            } else if (e.missingSince < 0) {
                e.missingSince = ticks;
            } else if (ticks - e.missingSince > PENDING_TTL_TICKS) {
                release(e);
                it.remove();
            }
        }
    }

    public void clear() {
        for (Entry e : byEntity.values()) {
            release(e);
        }
        byEntity.clear();
    }

    private void release(Entry e) {
        if (e != null && e.instance != null) {
            e.instance.release();
            e.instance = null;
        }
    }

    public void noteRejectedPacket() {
        if (rejectedPackets++ == 0) {
            NpcModelClient.LOG.warn("NPC 모델 패킷을 해석하지 못했습니다 (서버 ChacaNPC 와 모드 프로토콜 v{} 가 다를 수 있음) — 원래 외형으로 표시합니다",
                    NpcModelProtocol.VERSION);
        }
    }

    public int size() {
        return byEntity.size();
    }
}
