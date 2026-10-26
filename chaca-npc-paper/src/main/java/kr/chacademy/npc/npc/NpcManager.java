package kr.chacademy.npc.npc;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.ScheduleEntry;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Citizens NPC ↔ 캐릭터 파일 연결. 연결 정보는 Citizens NPC 데이터에 저장된다(chacanpc-id).
 */
public final class NpcManager implements Listener {

    public static final String DATA_KEY = "chacanpc-id";

    private final ChacaNpcPlugin plugin;
    private final Map<Integer, String> charByNpc = new ConcurrentHashMap<>();
    private final Map<String, Integer> npcByChar = new ConcurrentHashMap<>();

    public NpcManager(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    /** Citizens에 있는 NPC 중 우리 캐릭터와 연결된 것을 찾는다. */
    public void scan() {
        charByNpc.clear();
        npcByChar.clear();
        int count = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.data().has(DATA_KEY)) {
                Object id = npc.data().get(DATA_KEY);
                if (id != null) {
                    String charId = id.toString();
                    Integer cur = npcByChar.get(charId);
                    if (cur != null && cur != npc.getId()) {
                        // 같은 캐릭터가 NPC 둘에 연결됨: Citizens id 가 가장 작은 쪽만 쓴다 (클릭할 때마다 바뀌지 않게)
                        int keep = Math.min(cur, npc.getId());
                        int drop = Math.max(cur, npc.getId());
                        plugin.getLogger().warning("[ChacaNPC] 캐릭터 '" + charId + "'가 Citizens NPC #" + keep + " 와 #" + drop
                                + " 둘에 연결돼 있습니다. #" + keep + " 만 사용합니다 — #" + drop
                                + " 옆에서 /NPC관리 연결해제 하거나 다른 캐릭터로 /NPC관리 연결 하세요.");
                        if (keep == cur) {
                            continue;
                        }
                    } else if (cur == null) {
                        count++;
                    }
                    register(npc.getId(), charId);
                }
            }
        }
        plugin.getLogger().info("[ChacaNPC] 연결된 NPC " + count + "명");
    }

    /** 이 캐릭터가 이미 다른(아직 존재하고 같은 캐릭터로 표시된) NPC에 연결돼 있는지. */
    private boolean ownedByOther(String charId, int npcId) {
        Integer owner = npcByChar.get(charId);
        if (owner == null || owner == npcId) {
            return false;
        }
        NPC other = CitizensAPI.getNPCRegistry().getById(owner);
        if (other == null || !other.data().has(DATA_KEY)) {
            return false;
        }
        Object id = other.data().get(DATA_KEY);
        return id != null && charId.equals(id.toString());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        clickGate.remove(e.getPlayer().getUniqueId());
    }

    private void register(int npcId, String charId) {
        Integer old = npcByChar.put(charId, npcId);
        if (old != null && old != npcId) {
            charByNpc.remove(old);
        }
        charByNpc.put(npcId, charId);
    }

    @EventHandler
    public void onCitizensEnable(CitizensEnableEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::scan, 20L);
    }

    private final Map<java.util.UUID, Long> clickGate = new ConcurrentHashMap<>();
    private boolean warnedNoLink;

    /**
     * 연결된 NPC 클릭의 유일한 진입점. 우선순위: (1) Shift+아이템 = 선물 → (2) 열 수 있는 고정(스토리) 대화
     * → (3) AI 대화. MagicCodexBridge의 Citizens 클릭 처리에서는 이 NPC들이 제외된다(claim).
     * 다른 유저 스텟 보기(실제 Player만 대상, NPC 메타데이터 제외)는 건드리지 않는다.
     * 다른 플러그인이 먼저 취소한 클릭은 처리하지 않는다 (스토리 단계의 NPC 호출이 AI 대화·선물보다 먼저).
     */
    @EventHandler(ignoreCancelled = true)
    public void onRightClick(NPCRightClickEvent e) {
        NPC npc = e.getNPC();
        String charId = charByNpc.get(npc.getId());
        if (charId == null && npc.data().has(DATA_KEY)) {
            Object id = npc.data().get(DATA_KEY);
            if (id != null) {
                charId = id.toString();
                if (ownedByOther(charId, npc.getId())) {
                    return; // 같은 캐릭터가 다른 NPC에 이미 연결됨(중복): 이 NPC로 연결을 뒤집지 않는다
                }
                register(npc.getId(), charId);
            }
        }
        if (charId == null) {
            return;
        }
        CharacterSheet c = plugin.characters().get(charId);
        if (c == null) {
            return;
        }
        Player p = e.getClicker();
        long now = System.currentTimeMillis();
        Long last = clickGate.get(p.getUniqueId());
        if (last != null && now - last < 400) {
            return;
        }
        clickGate.put(p.getUniqueId(), now);

        // (1) 선물은 대화 버튼 3번 "선물 줄 거 있어" → 선물 창 (Shift+우클릭 선물은 2026-10-23 오너 결정으로 없앰)
        int npcId = npc.getId();
        org.bukkit.entity.Entity anchor = npc.getEntity();
        // (2) 고정 대화 → NONE 일 때만 (3) AI
        if (plugin.link().available()) {
            plugin.link().openStory(p, npcId, anchor).whenComplete((res, ex) -> {
                if (ex != null) {
                    plugin.getLogger().warning("[ChacaNPC] 고정 대화 확인 실패: " + ex.getClass().getSimpleName());
                    return; // 판단 실패 시 AI로 덮지 않는다
                }
                if ("NONE".equals(res) && p.isOnline() && c.openOnRightClick()) {
                    plugin.dialogue().open(p, c, npcId, false);
                } else if ("OPENED".equals(res) && plugin.appearance() != null) {
                    plugin.appearance().greet(npcId); // 스토리 대화가 열림: 3D 외형 인사 동작만 (클릭 처리 순서는 그대로)
                }
            });
        } else if (Bukkit.getPluginManager().isPluginEnabled("MagicCodexBridge")) {
            // Bridge는 켜져 있는데 NPC API 연결이 없음(초기화 실패·버전 불일치): Bridge가 이 클릭으로 고정 대화를
            // 열 수 있으므로 AI 대화를 같이 열지 않는다. (/말 로는 대화 가능)
            if (!warnedNoLink) {
                warnedNoLink = true;
                plugin.getLogger().warning("[ChacaNPC] MagicCodexBridge NPC API에 연결되지 않아 우클릭 AI 대화를 열지 않습니다 (/말 사용 가능)");
            }
        } else if (c.openOnRightClick()) {
            plugin.dialogue().open(p, c, npcId, false);
        }
    }

    /** MagicCodexBridge에 "이 NPC 클릭은 ChacaNPC가 처리" 를 알린다. */
    public boolean isClaimed(int npcId) {
        return charByNpc.containsKey(npcId);
    }

    public NPC spawn(CharacterSheet c, Location loc) {
        Integer existing = npcByChar.get(c.id());
        if (existing != null) {
            NPC old = CitizensAPI.getNPCRegistry().getById(existing);
            if (old != null) {
                old.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                return old;
            }
        }
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, c.name());
        npc.data().setPersistent(DATA_KEY, c.id());
        applySkin(npc, c);
        npc.spawn(loc);
        register(npc.getId(), c.id());
        return npc;
    }

    public void applySkin(NPC npc, CharacterSheet c) {
        if (c.skin() != null && !c.skin().isBlank()) {
            try {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(c.skin());
            } catch (Throwable t) {
                plugin.getLogger().warning("[ChacaNPC] 스킨 적용 실패 (" + c.id() + "): " + t.getMessage());
            }
        }
    }

    /**
     * NPC를 캐릭터와 연결한다. 캐릭터 하나는 NPC 하나에만 연결되므로, 같은 캐릭터로 표시돼 있던 다른 NPC의 연결은 지운다.
     *
     * @return 연결이 지워진 다른 NPC 수
     */
    public int link(NPC npc, CharacterSheet c) {
        int cleared = 0;
        for (NPC other : CitizensAPI.getNPCRegistry()) {
            if (other.getId() == npc.getId() || !other.data().has(DATA_KEY)) {
                continue;
            }
            Object id = other.data().get(DATA_KEY);
            if (id != null && c.id().equals(id.toString())) {
                other.data().remove(DATA_KEY);
                charByNpc.remove(other.getId());
                cleared++;
            }
        }
        // 이 NPC가 다른 캐릭터에 연결돼 있었다면 그 연결도 정리
        String before = charByNpc.get(npc.getId());
        if (before != null && !before.equals(c.id())) {
            npcByChar.remove(before, npc.getId());
        }
        npc.data().setPersistent(DATA_KEY, c.id());
        register(npc.getId(), c.id());
        return cleared;
    }

    public boolean unlink(NPC npc) {
        String charId = charByNpc.remove(npc.getId());
        npc.data().remove(DATA_KEY);
        if (charId != null) {
            npcByChar.remove(charId);
            return true;
        }
        return false;
    }

    public NPC npcFor(String charId) {
        Integer id = npcByChar.get(charId);
        return id == null ? null : CitizensAPI.getNPCRegistry().getById(id);
    }

    public Integer npcIdFor(String charId) {
        return npcByChar.get(charId);
    }

    public String charIdFor(int npcId) {
        return charByNpc.get(npcId);
    }

    public List<String> linkedCharacters() {
        return new ArrayList<>(npcByChar.keySet());
    }

    /** 플레이어 근처에서 가장 가까운 연결 NPC. */
    public NPC nearest(Player p, double maxDistance) {
        NPC best = null;
        double bestD = maxDistance * maxDistance;
        for (Integer id : charByNpc.keySet()) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
                continue;
            }
            Location l = npc.getEntity().getLocation();
            if (l.getWorld() != p.getWorld()) {
                continue;
            }
            double d = l.distanceSquared(p.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = npc;
            }
        }
        return best;
    }

    /** 플레이어가 바라보는(가까운) 아무 Citizens NPC — /NPC관리 연결 용. */
    public NPC nearestAny(Player p, double maxDistance) {
        NPC best = null;
        double bestD = maxDistance * maxDistance;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned() || npc.getEntity() == null) {
                continue;
            }
            Location l = npc.getEntity().getLocation();
            if (l.getWorld() != p.getWorld()) {
                continue;
            }
            double d = l.distanceSquared(p.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = npc;
            }
        }
        return best;
    }

    public Location location(int npcId) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc == null) {
            return null;
        }
        if (npc.isSpawned() && npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    public void face(int npcId, Player p) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null && npc.isSpawned()) {
            npc.faceLocation(p.getEyeLocation());
        }
    }

    /** NPC가 있는 월드(없으면 첫 월드)의 시간. */
    private World worldOf(CharacterSheet c) {
        Integer id = npcByChar.get(c.id());
        if (id != null) {
            Location l = location(id);
            if (l != null && l.getWorld() != null) {
                return l.getWorld();
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    public int minuteOfDay(CharacterSheet c) {
        World w = worldOf(c);
        return w == null ? 720 : ScheduleEntry.minuteFromWorldTime(w.getTime());
    }

    public int currentSlot(CharacterSheet c) {
        return ScheduleEntry.currentIndex(c.schedule(), minuteOfDay(c));
    }

    public String partOfDay(CharacterSheet c) {
        return ScheduleEntry.partOfDay(minuteOfDay(c));
    }
}
