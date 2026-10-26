package school.magiccodex.paper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import org.bukkit.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.ArtifactProtocol;
import school.magiccodex.protocol.ArtifactProtocol.Entry;

/**
 * Artifact codex (아티팩트 도감). Definitions live in plugins/MagicCodexBridge/artifacts.yml so the owner can edit them at
 * any time (/아티팩트관리 새로고침); clients receive the catalogue from here, so the mod never needs a rebuild for content.
 *
 * Ownership: artifact-only MariaDB authority, migrated once from both local LuckPerms stores.
 * Equipped list: player PDC key "artifact_equipped", shared between school/wild through PlayerStateStore
 * (MariaDB codex_player_state.artifact_equipped, same lease as mana/accessories; 2026-10-15).
 * Use key (2026-10-15): artifacts with `use: true` can be used with the client's artifact key (default X). The server
 * checks ownership, equipment and the per-player cooldown, then fires {@link ArtifactUseEvent}; the cooldown starts
 * only when a listener reports the effect happened. Abilities: {@link ArtifactEffects} (2026-10-15).
 * Use cooldowns live in memory and travel with the shared player state (artifact_cooldowns) to the other server.
 *
 * Summon (유물함 소환, 2026-10-15): /아티팩트소환 opens the client window. [소환하기] spends one ItemsAdder key item
 * (artifacts.yml summon.item, default item:random_artifact), rolls a grade by summon.weights and then a uniform
 * artifact of that grade. New ownership and duplicate classification commit atomically; duplicates mail
 * summon.duplicate.item (item:artifact_shard). The client only plays the reveal; the result is decided here.
 * 천장(pity)은 2026-10-26 오너 결정(메모 #98)으로 폐기했다. 대신 중복 조각을 상점(아티팩트 교환소)에서
 * 등급 확정 뽑기권(summon.tickets)으로 바꾼다. 확정권을 우클릭하면 같은 소환 창·같은 연출로 그 등급 1회 뽑기
 * (열쇠 대신 확정권 1장 소모, 중복이면 조각 — 열쇠 뽑기와 같은 영수증·공유 DB 흐름).
 */
final class ArtifactBridge implements PluginMessageListener, Listener {
    private final MagicCodexBridge plugin;
    private final NamespacedKey equippedKey;
    private volatile List<Entry> catalog = List.of();
    private Set<String> definedIds=Set.of();
    private volatile long revision;
    private int maxEquipped = 1;
    private boolean ownsAll;
    /** Players whose client has asked at least once (only they get pushes after a reload). */
    private final Set<UUID> listening = new HashSet<>();
    private final Map<UUID, Long> limits = new HashMap<>();
    /** player -> artifact id -> System.currentTimeMillis() when it is ready again. Expired entries are dropped lazily. */
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    // ---- summon settings (artifacts.yml summon:)
    private String summonItem = "item:random_artifact", shardItem = "item:artifact_shard", summonItemName = "유물함 열쇠";
    private int summonCost = 1;
    /** 등급 확정 뽑기권 (#98): 등급 번호(0 일반 ~ 3 전설) → ItemsAdder 키·표시 이름. 빈 키 = 그 등급 확정권 없음. */
    private final String[] ticketItem = {"item:artifact_ticket_common", "item:artifact_ticket_rare", "item:artifact_ticket_unique", "item:artifact_ticket_legendary"};
    private final String[] ticketName = {"일반 확정 뽑기권", "고급 확정 뽑기권", "유일 확정 뽑기권", "전설 확정 뽑기권"};
    private final double[] weights = {5, 3, 1, 0.1};
    /** Grades shown on the four unpicked chests: cosmetic only, tilted toward high grades for a near-miss feel. */
    private final double[] decoyWeights = {4, 3, 2, 1};
    private final int[] shardAmount = {1, 1, 1, 1};
    private String mailTitle = "아티팩트 조각", mailBody = "이미 가진 아티팩트가 나와 조각으로 바꿔 드렸습니다.";
    private final ItemsAdderItems itemsAdder;
    private final ArtifactOwnershipService ownership;
    private final java.util.Random random = new java.security.SecureRandom();

    ArtifactBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        equippedKey = new NamespacedKey(plugin, "artifact_equipped");
        itemsAdder = new ItemsAdderItems(plugin.getLogger());
        if (!new File(plugin.getDataFolder(), "artifacts.yml").exists()) plugin.saveResource("artifacts.yml", false);
        reload();
        ownership=new ArtifactOwnershipService(plugin,this::ownershipChanged,()->mailTitle,()->mailBody);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, ArtifactProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, ArtifactProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CmdKit.register(plugin, "/아티팩트뽑기·/유저관리 아티팩트·/컨텐츠관리 아티팩트·/테스트 아티팩트", this::commands);

    }

    // ------------------------------------------------------------------ catalogue

    void reload() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "artifacts.yml"));
        int max = Math.clamp(yaml.getInt("settings.max-equipped", 1), 1, ArtifactProtocol.MAX_EQUIPPED);
        boolean all = yaml.getBoolean("settings.test-owns-all", false);
        ConfigurationSection root = yaml.getConfigurationSection("artifacts");
        definedIds=root==null?Set.of():Set.copyOf(root.getKeys(false));
        record Row(Entry entry, int order) {}
        var rows = new ArrayList<Row>();
        if (root != null) for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) throw new IllegalArgumentException("artifacts." + id + " 형식 오류");
            if (!s.getBoolean("enabled", true)) continue;
            if (!ArtifactProtocol.validId(id)) throw new IllegalArgumentException("아이디는 영문 소문자·숫자·_ 만: " + id);
            int grade = grade(s.getString("grade", ""));
            if (grade < 0) throw new IllegalArgumentException(id + ".grade 는 common/rare/unique/legendary (또는 일반/고급/유일/전설)");
            String name = s.getString("name", "").trim();
            if (name.isEmpty() || name.length() > ArtifactProtocol.MAX_NAME) throw new IllegalArgumentException(id + ".name 은 1~40자");
            String description = clip(s.getString("description", ""), ArtifactProtocol.MAX_DESCRIPTION, id + ".description");
            String effect = clip(s.getString("effect", ""), ArtifactProtocol.MAX_EFFECT, id + ".effect");
            String icon = clip(s.getString("icon", ""), ArtifactProtocol.MAX_ICON, id + ".icon");
            if (!icon.isBlank() && !icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+\\.png")) throw new IllegalArgumentException(id + ".icon 은 네임스페이스:경로.png 형식");
            int cooldown = ArtifactProtocol.PASSIVE;
            if (s.getBoolean("use", false)) {
                cooldown = s.getInt("cooldown", 0);
                if (cooldown < 0 || cooldown > ArtifactProtocol.MAX_COOLDOWN) throw new IllegalArgumentException(id + ".cooldown 은 0~" + ArtifactProtocol.MAX_COOLDOWN + "초");
            }
            rows.add(new Row(new Entry(id, name, grade, description, effect, icon, cooldown), s.getInt("order", 0)));
        }
        if (rows.size() > ArtifactProtocol.MAX_ARTIFACTS) throw new IllegalArgumentException("아티팩트는 최대 " + ArtifactProtocol.MAX_ARTIFACTS + "개");
        rows.sort(Comparator.comparingInt((Row r) -> r.entry().grade()).thenComparingInt(Row::order).thenComparing(r -> r.entry().id()));
        var next = rows.stream().map(Row::entry).toList();
        ArtifactProtocol.catalog(0, next); // fail now (not on send) if a part would be too large
        var crc = new CRC32();
        crc.update((max + "|" + next).getBytes(StandardCharsets.UTF_8));
        // summon settings
        var sm = yaml.getConfigurationSection("summon");
        String item = sm == null ? "item:random_artifact" : sm.getString("item", "item:random_artifact").trim();
        String shard = sm == null ? "item:artifact_shard" : sm.getString("duplicate.item", "item:artifact_shard").trim();
        if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !shard.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("summon.item / summon.duplicate.item 은 아이템애더 네임스페이스:아이디");
        int cost = sm == null ? 1 : sm.getInt("cost", 1);
        if (cost < 1 || cost > 64) throw new IllegalArgumentException("summon.cost 는 1~64");
        double[] w = new double[4], dw = new double[4]; int[] shards = new int[4]; double total = 0, decoyTotal = 0;
        for (int g = 0; g < 4; g++) {
            String key = ArtifactProtocol.GRADE_KEYS[g];
            w[g] = sm == null ? new double[]{5, 3, 1, 0.1}[g] : sm.getDouble("weights." + key, new double[]{5, 3, 1, 0.1}[g]);
            shards[g] = sm == null ? 1 : sm.getInt("duplicate.amount." + key, 1);
            dw[g] = sm == null ? new double[]{4, 3, 2, 1}[g] : sm.getDouble("decoy-weights." + key, new double[]{4, 3, 2, 1}[g]);
            if (dw[g] < 0 || !Double.isFinite(dw[g])) throw new IllegalArgumentException("summon.decoy-weights." + key + " 는 0 이상");
            decoyTotal += dw[g];
            if (w[g] < 0 || !Double.isFinite(w[g])) throw new IllegalArgumentException("summon.weights." + key + " 는 0 이상");
            if (shards[g] < 0 || shards[g] > 64) throw new IllegalArgumentException("summon.duplicate.amount." + key + " 는 0~64");
            total += w[g];
        }
        if (total <= 0) throw new IllegalArgumentException("summon.weights 합이 0");
        if (decoyTotal <= 0) throw new IllegalArgumentException("summon.decoy-weights 합이 0");
        // 등급 확정 뽑기권: summon.tickets.<등급>.item / .name. tickets 칸이 없으면 기본값(일반·고급·유일·전설), 등급을 빼면 그 확정권 없음.
        String[] ti = {"item:artifact_ticket_common", "item:artifact_ticket_rare", "item:artifact_ticket_unique", "item:artifact_ticket_legendary"};
        String[] tn = {"일반 확정 뽑기권", "고급 확정 뽑기권", "유일 확정 뽑기권", "전설 확정 뽑기권"};
        var ts = sm == null ? null : sm.getConfigurationSection("tickets");
        if (ts != null) {
            Arrays.fill(ti, ""); Arrays.fill(tn, "");
            for (String key : ts.getKeys(false)) {
                int g = grade(key);
                if (g < 0) throw new IllegalArgumentException("summon.tickets." + key + " 는 common/rare/unique/legendary (또는 일반/고급/유일/전설)");
                ti[g] = ts.getString(key + ".item", "").trim();
                tn[g] = clip(ts.getString(key + ".name", ArtifactProtocol.GRADE_LABELS[g] + " 확정 뽑기권"), 30, "summon.tickets." + key + ".name");
                if (!ti[g].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("summon.tickets." + key + ".item 은 아이템애더 네임스페이스:아이디");
            }
        }
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
            if (!ti[a].isEmpty() && (ti[a].equals(item) || ti[a].equals(shard) || a != b && ti[a].equals(ti[b])))
                throw new IllegalArgumentException("summon.tickets 의 아이템은 열쇠·조각·다른 확정권과 달라야 해요: " + ti[a]);
        catalog = next; revision = crc.getValue() == 0 ? 1 : crc.getValue(); maxEquipped = max; ownsAll = all;
        summonItem = item; shardItem = shard; summonCost = cost;
        System.arraycopy(ti, 0, ticketItem, 0, 4); System.arraycopy(tn, 0, ticketName, 0, 4);
        System.arraycopy(w, 0, weights, 0, 4); System.arraycopy(dw, 0, decoyWeights, 0, 4); System.arraycopy(shards, 0, shardAmount, 0, 4);
        if (sm != null) {
            mailTitle = sm.getString("duplicate.mail-title", mailTitle); mailBody = sm.getString("duplicate.mail-body", mailBody);
            summonItemName = clip(sm.getString("item-name", summonItemName), 30, "summon.item-name");
        }
    }

    private static int grade(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 4; i++) if (v.equals(ArtifactProtocol.GRADE_KEYS[i]) || v.equals(ArtifactProtocol.GRADE_LABELS[i])) return i;
        return -1;
    }

    private static String clip(String value, int max, String where) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) throw new IllegalArgumentException(where + " 는 최대 " + max + "자");
        return v;
    }

    // ------------------------------------------------------------------ ownership / equipped

    boolean temporaryAccess(Player p,String id){return ownsAll||(p.isOp()&&(p.hasPermission("magiccodex.artifact.own."+id)||p.hasPermission("magiccodex.artifact.own.*")));}
    boolean owns(Player p, String id) { return temporaryAccess(p,id) || ownership.owns(p.getUniqueId(),id); }
    private void ownershipChanged(Player p) {
        // Preserve raw shared equipment, including legacy OP-only choices. Effective access is filtered by equipped().
        if(!plugin.playerStateReady(p))return;
        plugin.artifactsChanged(p);push(p);
    }
    void close(){ownership.close();}

    /** Equipped ids that still exist and are still owned (stale ones are dropped silently). */
    List<String> equipped(Player p) {
        String raw = p.getPersistentDataContainer().getOrDefault(equippedKey, PersistentDataType.STRING, "");
        var known = new HashSet<String>(); for (Entry e : catalog) known.add(e.id());
        var out = new ArrayList<String>();
        for (String id : raw.split(",")) {
            if (id.isBlank() || !known.contains(id) || !owns(p, id) || out.contains(id)) continue;
            out.add(id);
            if (out.size() >= maxEquipped) break;
        }
        return out;
    }

    private void store(Player p, List<String> ids) {
        if (ids.isEmpty()) p.getPersistentDataContainer().remove(equippedKey);
        else p.getPersistentDataContainer().set(equippedKey, PersistentDataType.STRING, String.join(",", ids));
    }

    // ------------------------------------------------------------------ network

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!ArtifactProtocol.REQUEST.equals(channel) || !p.isOnline()) return;
        ArtifactProtocol.Request r;
        try { r = ArtifactProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        long now = System.currentTimeMillis();
        if (now < limits.getOrDefault(p.getUniqueId(), 0L)) return;
        limits.put(p.getUniqueId(), now + 150);
        listening.add(p.getUniqueId());
        String notice = "";
        if (r.action() == ArtifactProtocol.EQUIP || r.action() == ArtifactProtocol.UNEQUIP) {
            if (!plugin.playerStateReady(p) || !ownership.ready(p.getUniqueId())) notice = "캐릭터 정보를 불러오는 중입니다.";
            else if (catalog.stream().noneMatch(e -> e.id().equals(r.id()))) notice = "도감에 없는 아티팩트입니다.";
            else if (!owns(p, r.id())) notice = "아직 발견하지 못한 아티팩트입니다.";
            else {
                var list = new ArrayList<>(equipped(p));
                if (r.action() == ArtifactProtocol.EQUIP) {
                    if (list.contains(r.id())) notice = "이미 장착 중입니다.";
                    else {
                        if (list.size() >= maxEquipped) {
                            if (maxEquipped == 1) list.clear();
                            else { notice = "장착 칸이 가득 찼습니다. 먼저 하나를 해제해 주세요."; }
                        }
                        if (notice.isEmpty()) { list.add(r.id()); store(p, list); notice = "장착했습니다."; plugin.artifactsChanged(p); }
                    }
                } else {
                    if (list.remove(r.id())) { store(p, list); notice = "해제했습니다."; plugin.artifactsChanged(p); } else notice = "장착 중이 아닙니다.";
                }
            }
        } else if (r.action() == ArtifactProtocol.USE) {
            notice = use(p, r.id(), now);
        } else if (r.action() == ArtifactProtocol.SUMMON_INFO || r.action() == ArtifactProtocol.SUMMON || r.action() == ArtifactProtocol.SUMMON_MULTI) {
            if (r.knownCatalog() != revision) send(p, true, "");
            if (r.action() == ArtifactProtocol.SUMMON_MULTI || r.action() == ArtifactProtocol.SUMMON) {
                summonShared(p,r.action()==ArtifactProtocol.SUMMON_MULTI?ArtifactProtocol.MULTI:1);
            } else sendSummon(p, ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_STATE, keys(p), summonInfoText()));
            return;
        }
        send(p, r.knownCatalog() != revision, notice);
    }

    private Entry entry(String id) { for (Entry e : catalog) if (e.id().equals(id)) return e; return null; }

    private long remaining(UUID player, String id, long now) {
        var map = readyAt.get(player);
        if (map == null) return 0;
        map.values().removeIf(t -> t <= now);
        if (map.isEmpty()) { readyAt.remove(player); return 0; }
        return Math.max(0, map.getOrDefault(id, 0L) - now);
    }

    /** Use cooldowns as "id=readyAtMillis;" for the shared player state (school/wild). */
    String cooldownText(UUID player) {
        var map = readyAt.get(player);
        if (map == null) return "";
        long now = System.currentTimeMillis();
        var out = new StringBuilder();
        for (var e : map.entrySet()) if (e.getValue() > now && out.length() < 4000) out.append(e.getKey()).append('=').append(e.getValue()).append(';');
        return out.toString();
    }

    /** Replaces this player's cooldowns with the shared state's. */
    void restoreCooldowns(UUID player, String text) {
        readyAt.remove(player);
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        var map = new HashMap<String, Long>();
        for (String part : text.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || !ArtifactProtocol.validId(pair[0])) continue;
            try { long until = Long.parseLong(pair[1]); if (until > now && until - now <= ArtifactProtocol.MAX_COOLDOWN * 1000L) map.put(pair[0], until); }
            catch (NumberFormatException ignored) {}
        }
        if (!map.isEmpty()) readyAt.put(player, map);
    }

    /** Artifact use key. Returns the action-bar notice. */
    private String use(Player p, String id, long now) {
        if (!plugin.playerStateReady(p)) return "캐릭터 정보를 불러오는 중입니다.";
        Entry e = entry(id);
        if (e == null || !owns(p, id) || !equipped(p).contains(id)) return "장착한 아티팩트가 없습니다.";
        if (!e.usable()) return e.name() + "은(는) 사용하는 아티팩트가 아닙니다.";
        long left = remaining(p.getUniqueId(), id, now);
        if (left > 0) return e.name() + " 재사용 대기 " + ((left + 999) / 1000) + "초";
        var attempt = new ArtifactUseAttemptEvent(p, id);
        Bukkit.getPluginManager().callEvent(attempt);
        if (attempt.isCancelled()) return attempt.getMessage() != null ? attempt.getMessage() : e.name() + "은(는) 지금 사용할 수 없습니다.";
        var event = new ArtifactUseEvent(p, id, e.cooldown());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isUsed()) return event.getResponse() != null ? event.getResponse() : e.name() + "의 효과는 아직 준비 중입니다.";
        if (e.cooldown() > 0) { readyAt.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(id, now + e.cooldown() * 1000L); plugin.markPlayerStateDirty(p); }
        return event.getResponse() != null ? event.getResponse() : "";
    }

    // ------------------------------------------------------------------ summon

    private int keys(Player p) { return count(p, summonItem); }
    private int count(Player p, String key) {
        if (!itemsAdder.available() || key.isEmpty()) return 0;
        int n = 0;
        for (var item : p.getInventory().getStorageContents()) if (key.equals(itemsAdder.idOf(item))) n += item.getAmount();
        return n;
    }

    private boolean takeKeys(Player p, int amount) { return take(p, summonItem, amount); }
    /** 인벤토리 저장 칸에서 이 ItemsAdder 아이템을 amount 개 뺀다. 모자라면 아무것도 빼지 않고 false. */
    private boolean take(Player p, String key, int amount) {
        if (count(p, key) < amount) return false;
        var inv = p.getInventory(); var contents = inv.getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            var item = contents[i];
            if (!key.equals(itemsAdder.idOf(item))) continue;
            int take = Math.min(left, item.getAmount());
            if (take == item.getAmount()) inv.setItem(i, null); else item.setAmount(item.getAmount() - take);
            left -= take;
        }
        return left == 0;
    }

    /** Grade among the summonable grades, by summon.weights. */
    private int rollGrade() {
        double total = 0; for (int g = 0; g < 4; g++) if (hasGrade(g)) total += weights[g];
        double x = random.nextDouble() * total;
        for (int g = 0; g < 4; g++) { if (!hasGrade(g)) continue; x -= weights[g]; if (x < 0) return g; }
        for (int g = 3; g >= 0; g--) if (hasGrade(g) && weights[g] > 0) return g;
        return 0;
    }
    /** 그 등급 아티팩트 하나를 균등하게 고른다 (그 등급이 도감에 있어야 한다). */
    private ArtifactOwnershipStore.Choice choice(Player p, int grade) {
        var pool = catalog.stream().filter(e -> e.grade() == grade).toList();
        var won = pool.get(random.nextInt(pool.size()));
        return new ArtifactOwnershipStore.Choice(won.id(), grade, shardAmount[grade], temporaryAccess(p, won.id()));
    }
    private boolean inCatalog(int g) { for (Entry e : catalog) if (e.grade() == g) return true; return false; }
    /** Cosmetic grade for an unpicked chest (no artifact behind it, so the catalogue does not limit it). */
    private int rollDecoy() {
        double total = 0; for (double w : decoyWeights) total += w;
        double x = random.nextDouble() * total;
        for (int g = 0; g < 4; g++) { x -= decoyWeights[g]; if (x < 0 && decoyWeights[g] > 0) return g; }
        return 0;
    }
    private boolean hasGrade(int g) { if (weights[g] <= 0) return false; for (Entry e : catalog) if (e.grade() == g) return true; return false; }

    /** All paid pulls enter a private durable spool; DB outages retry without recharging or rerolling. */
    private void summonShared(Player p,int count) {
        int cost=summonCost*count,have=keys(p);
        if(!plugin.playerStateReady(p)||!ownership.ready(p.getUniqueId())||!itemsAdder.available()){
            sendSummon(p,ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_REFUSED,have,"§e정보를 불러오고 있습니다. 잠시 후 다시 시도해 주세요."));return;
        }
        boolean any=false;for(int g=0;g<4;g++)any|=hasGrade(g);
        if(!any||have<cost){sendSummon(p,ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_REFUSED,have,"소환 재료가 부족하거나 소환할 수 있는 아티팩트가 없습니다."));return;}
        var choices=new ArrayList<ArtifactOwnershipStore.Choice>();
        for(int i=0;i<count;i++)choices.add(choice(p,rollGrade()));
        boolean accepted=ownership.purchase(p.getUniqueId(),choices,shardItem,()->takeKeys(p,cost),rows->{
            auditSummon(p,rows,summonItem+" "+cost);
            if(!p.isOnline())return;send(p,false,"");
            if(count==1)sendResult(p,rows.getFirst(),"");
            else {var pulls=rows.stream().map(x->new ArtifactProtocol.Pull(x.id(),x.grade(),x.fresh(),x.shards())).toList();if(p.getListeningPluginChannels().contains(ArtifactProtocol.RESPONSE))p.sendPluginMessage(plugin,ArtifactProtocol.RESPONSE,ArtifactProtocol.encode(new ArtifactProtocol.SummonMulti(keys(p),pulls,"")));}
        });
        if(!accepted)sendSummon(p,ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_REFUSED,keys(p),"소환을 처리하지 못했습니다. 재료가 차감됐다면 관리자에게 문의해 주세요."));
    }

    /** 1회 결과 프레임 (유물함 고르기 → 열기 연출). 나머지 네 상자의 등급은 장식용. */
    private void sendResult(Player p, ArtifactOwnershipStore.Pull x, String notice) {
        int[] decoys = new int[ArtifactProtocol.DECOYS];
        for (int i = 0; i < decoys.length; i++) decoys[i] = rollDecoy();
        sendSummon(p, new ArtifactProtocol.Summon(ArtifactProtocol.SUMMON_RESULT, keys(p), x.id(), x.grade(), x.fresh(), x.shards(), decoys, notice));
    }

    // ------------------------------------------------------------------ 등급 확정 뽑기권 (#98, 2026-10-26)

    /** 손에 든 아이템이 확정권이면 그 등급, 아니면 -1. */
    private int ticketGrade(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) return -1;
        String id = itemsAdder.idOf(item);
        if (id == null) return -1;
        for (int g = 0; g < 4; g++) if (!ticketItem[g].isEmpty() && ticketItem[g].equals(id)) return g;
        return -1;
    }

    /** 확정권 우클릭: 확정권 1장을 내고 그 등급으로 1회 뽑은 뒤, 소환 창에서 열쇠 뽑기와 같은 연출로 보여 준다. */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void useTicket(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        var hand = e.getHand();
        if (hand == null) return;
        Player p = e.getPlayer();
        int grade = ticketGrade(p.getInventory().getItem(hand));
        if (grade < 0) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now < limits.getOrDefault(p.getUniqueId(), 0L)) return;   // 두 손 이벤트·연타
        limits.put(p.getUniqueId(), now + 500);
        String name = ticketName[grade];
        if (!p.getListeningPluginChannels().contains(ArtifactProtocol.RESPONSE)) { CmdKit.notify(p, "warning", "artifact-mod", name + "을(를) 쓸 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
        if (!plugin.playerStateReady(p) || !ownership.ready(p.getUniqueId()) || !itemsAdder.available()) { CmdKit.notify(p, "warning", "artifact-ticket", "정보를 불러오고 있어요", "잠시 후 다시 써 주세요", 2000); return; }
        if (!inCatalog(grade)) { CmdKit.notify(p, "warning", "artifact-ticket", ArtifactProtocol.GRADE_LABELS[grade] + " 아티팩트가 아직 없어요", "확정권은 그대로 남아 있어요", 3000); return; }
        var choices = List.of(choice(p, grade));
        String key = ticketItem[grade];
        boolean accepted = ownership.purchase(p.getUniqueId(), choices, shardItem, () -> take(p, key, 1), rows -> {
            auditSummon(p, rows, key + " 1");
            if (!p.isOnline()) return;
            send(p, false, "");
            sendResult(p, rows.getFirst(), name + " 사용 · " + ArtifactProtocol.GRADE_LABELS[grade] + " 확정");
        });
        if (!accepted) { CmdKit.notify(p, "warning", "artifact-ticket", name + "을(를) 처리하지 못했어요", "확정권이 줄었다면 관리자에게 문의해 주세요", 3000); return; }
        // 창을 먼저 연다. 결과(공유 DB 처리 뒤, 보통 바로)는 열린 창에 와서 기존 연출(고르기 → 열기)을 그대로 탄다.
        listening.add(p.getUniqueId());
        send(p, true, "");
        sendSummon(p, ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_OPEN, keys(p), summonInfoText()));
    }

    /** 운영 기록 (메모 #92): 뽑기 결과 한 줄. material = "재료키 개수" (열쇠 또는 확정권). */
    private void auditSummon(Player p, List<ArtifactOwnershipStore.Pull> rows, String material) {
        var text = new StringBuilder();
        for (var x : rows) {
            if (text.length() > 0) text.append(", ");
            text.append(x.id()).append('(').append(x.grade() >= 0 && x.grade() < ArtifactProtocol.GRADE_LABELS.length ? ArtifactProtocol.GRADE_LABELS[x.grade()] : x.grade())
                    .append(x.fresh() ? ",새것" : ",중복+조각" + x.shards()).append(')');
        }
        AuditLog.log(p, AuditLog.아티팩트, "아티팩트 뽑기 " + rows.size() + "회 · " + material + " · " + AuditText.clip(text.toString(), 180),
                "횟수", rows.size(), "재료", material, "결과", text.toString());
    }

    private void sendSummon(Player p, ArtifactProtocol.Summon frame) {
        if (!p.getListeningPluginChannels().contains(ArtifactProtocol.RESPONSE)) return;
        p.sendPluginMessage(plugin, ArtifactProtocol.RESPONSE, ArtifactProtocol.encode(frame));
    }

    /** /아티팩트뽑기 (본인), /테스트 아티팩트 뽑기 [닉네임]: opens the client window. OPEN/STATE notices carry the grade chances for the window. */
    private void openSummon(CommandSender sender, Player target) throws CommandSyntaxException {
        if (!target.getListeningPluginChannels().contains(ArtifactProtocol.RESPONSE)) {
            if (sender == target) { CmdKit.notify(target, "warning", "artifact-mod", "아티팩트 뽑기 창을 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
            throw ChacaApi.error(plugin.names().name(target) + " 님은 차카데미 모드가 없어 뽑기 창을 열 수 없어요");
        }
        listening.add(target.getUniqueId());
        send(target, true, "");
        sendSummon(target, ArtifactProtocol.Summon.simple(ArtifactProtocol.SUMMON_OPEN, keys(target), summonInfoText()));
    }

    /** After the shared state (school/wild) loaded: send equipped + cooldowns so the client's X-key HUD and codex match. */
    void push(Player p) { send(p,true,"");listening.add(p.getUniqueId()); }

    private void send(Player p, boolean withCatalog, String notice) {
        if (!p.getListeningPluginChannels().contains(ArtifactProtocol.RESPONSE)) return;
        var snapshot = catalog; long rev = revision;
        if (withCatalog) for (byte[] part : ArtifactProtocol.catalog(rev, snapshot)) p.sendPluginMessage(plugin, ArtifactProtocol.RESPONSE, part);
        boolean[] owned = new boolean[snapshot.size()];
        for (int i = 0; i < owned.length; i++) owned[i] = owns(p, snapshot.get(i).id());
        String n = notice.length() > ArtifactProtocol.MAX_NOTICE ? notice.substring(0, ArtifactProtocol.MAX_NOTICE) : notice;
        var equipped = equipped(p);
        long now = System.currentTimeMillis();
        int[] cooldown = new int[equipped.size()];
        for (int i = 0; i < cooldown.length; i++)
            cooldown[i] = (int) Math.min(remaining(p.getUniqueId(), equipped.get(i), now), ArtifactProtocol.MAX_COOLDOWN * 1000L);
        p.sendPluginMessage(plugin, ArtifactProtocol.RESPONSE,
                ArtifactProtocol.encode(new ArtifactProtocol.State(rev, maxEquipped, owned, equipped, n, cooldown)));
    }

    /** OPEN/STATE notice: "<key item name>\u0001<cost>\u0001<grade chances>" for the window's guide text. */
    private String summonInfoText() { return summonItemName + "\u0001" + summonCost + "\u0001" + rates(); }

    /** Grade chances as shown to players, e.g. 일반 55.0% / 고급 33.0% / 유일 11.0% / 전설 1.1%. */
    private String rates() {
        double total = 0; for (int g = 0; g < 4; g++) if (hasGrade(g)) total += weights[g];
        var b = new StringBuilder();
        for (int g = 0; g < 4; g++) {
            if (b.length() > 0) b.append(" / ");
            b.append(ArtifactProtocol.GRADE_LABELS[g]).append(' ').append(total <= 0 || !hasGrade(g) ? "0" : String.format(java.util.Locale.ROOT, "%.1f", weights[g] * 100 / total)).append('%');
        }
        return b.toString();
    }

    @EventHandler public void quit(PlayerQuitEvent e) { listening.remove(e.getPlayer().getUniqueId()); limits.remove(e.getPlayer().getUniqueId()); ownership.forget(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ 명령 (2026-10-20)

    private ArtifactEffects effects;
    /** ArtifactEffects가 만들어질 때 자신을 알린다 (쿨타임초기화·보관품돌려주기). */
    void effects(ArtifactEffects fx) { effects = fx; }

    /** 등록된(켜진) 아티팩트 ID → 이름. */
    private Map<String, String> names() { var out = new LinkedHashMap<String, String>(); for (Entry e : catalog) out.put(e.id(), e.name()); return out; }
    private String label(String id) { Entry e = entry(id); return e == null ? id : e.name(); }

    private String artifactArg(CommandContext<CommandSourceStack> ctx, boolean anyDefined) throws CommandSyntaxException {
        String input = CmdKit.text(ctx, "아티팩트");
        String id = AdminCommandRules.resolve(names(), input);
        if (id == null && anyDefined && ArtifactProtocol.validId(input) && definedIds.contains(input)) id = input;
        if (id == null) throw ChacaApi.error("아티팩트를 찾을 수 없어요: " + input);
        return id;
    }

    private void commands() {
        ChacaApi.playerCommand(plugin, CmdKit.literal("아티팩트뽑기").executes(CmdKit.run(ctx -> { Player p = CmdKit.self(ctx); openSummon(p, p); })), "아티팩트 뽑기 창 열기", List.of());
        var names = (java.util.function.Supplier<Collection<String>>) () -> AdminCommandRules.catalogChoices(names());
        var user = CmdKit.literal("아티팩트")
                .then(CmdKit.literal("보기").executes(CmdKit.run(this::show)))
                .then(CmdKit.literal("주기").then(CmdKit.word("아티팩트", names).executes(CmdKit.run(ctx -> own(ctx, artifactArg(ctx, false), true)))))
                .then(CmdKit.literal("회수").then(CmdKit.word("아티팩트", names).executes(CmdKit.run(ctx -> own(ctx, artifactArg(ctx, true), false)))))
                .then(CmdKit.confirm(CmdKit.literal("초기화"), ctx -> own(ctx, null, false)))
                .then(CmdKit.literal("장착해제").executes(CmdKit.run(ctx -> unequip(ctx, null)))
                        .then(CmdKit.wordIn("아티팩트", ctx -> { UUID id = CmdKit.targetOf(ctx); Player p = id == null ? null : Bukkit.getPlayer(id); return p == null ? List.of() : equipped(p).stream().map(a -> AdminCommandRules.compact(label(a))).toList(); })
                                .executes(CmdKit.run(ctx -> unequip(ctx, artifactArg(ctx, false))))))
                .then(CmdKit.literal("쿨타임초기화").executes(CmdKit.run(this::resetCooldowns)))
                .then(CmdKit.literal("보관품돌려주기").executes(CmdKit.run(this::returnKept)))
                .then(CmdKit.literal("뽑기기록")
                        .then(CmdKit.literal("보기").executes(CmdKit.run(this::history)))
                        .then(CmdKit.literal("다시주기").then(CmdKit.argument("번호", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200)).executes(CmdKit.run(ctx -> fixHistory(ctx, true)))))
                        .then(CmdKit.literal("완료처리").then(CmdKit.argument("번호", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200)).executes(CmdKit.run(ctx -> fixHistory(ctx, false))))));
        ChacaApi.branch(plugin, ChacaApi.유저관리, user, "아티팩트 보유·장착·쿨타임·보관품·뽑기 기록", "아티팩트 보기");
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, CmdKit.literal("아티팩트").then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> list(CmdKit.sender(ctx))))), "등록된 아티팩트와 뽑기 확률", "아티팩트 목록");
        var test = CmdKit.literal("아티팩트")
                .then(CmdKit.literal("전체보유")
                        .then(CmdKit.literal("켜기").executes(CmdKit.run(ctx -> ownsAll(ctx, true))))
                        .then(CmdKit.literal("끄기").executes(CmdKit.run(ctx -> ownsAll(ctx, false)))))
                .then(CmdKit.literal("뽑기").executes(CmdKit.run(ctx -> openSummon(CmdKit.sender(ctx), CmdKit.self(ctx))))
                        .then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> openSummon(CmdKit.sender(ctx), ChacaApi.resolveOnline(ctx, "닉네임"))))));
        ChacaApi.branch(plugin, ChacaApi.테스트, test, "아티팩트 전체 보유 테스트 모드·뽑기 창", "아티팩트 뽑기");
        ChacaApi.reload(plugin, "아티팩트", "artifacts.yml (아티팩트 도감·뽑기)", () -> {
            reload();
            for (Player p : Bukkit.getOnlinePlayers()) if (listening.contains(p.getUniqueId())) send(p, true, "");
            plugin.getLogger().info("아티팩트 " + catalog.size() + "개 (장착 최대 " + maxEquipped + (ownsAll ? ", 전원 보유 테스트 모드" : "") + "), 뽑기 " + summonItem + " " + summonCost + "개, 확률 " + rates());
        });
        ChacaApi.summary(plugin, "아티팩트", id -> {
            Player p = Bukkit.getPlayer(id);
            long owned = catalog.stream().filter(e -> p != null ? owns(p, e.id()) : ownership.owns(id, e.id())).count();
            if (p == null && owned == 0) return "";
            return "보유 " + owned + " / " + catalog.size() + (p == null ? "" : " · 장착 " + String.join(", ", equipped(p).stream().map(this::label).toList()));
        });
    }

    private void list(CommandSender sender) {
        var out = new ArrayList<String>();
        out.add("── 아티팩트 목록 (" + catalog.size() + "개, 장착 최대 " + maxEquipped + ") ──");
        for (Entry e : catalog) out.add("[" + ArtifactProtocol.GRADE_LABELS[e.grade()] + "] " + e.name() + "  (" + e.id() + ")" + (e.usable() ? " · 사용형" + (e.cooldown() > 0 ? " " + e.cooldown() + "초" : "") : ""));
        out.add("뽑기: " + summonItemName + " " + summonCost + "개 · 확률 " + rates());
        var tickets = new ArrayList<String>();
        for (int g = 0; g < 4; g++) if (!ticketItem[g].isEmpty()) tickets.add(ticketName[g] + " " + ticketItem[g]);
        out.add("확정권: " + (tickets.isEmpty() ? "없음" : String.join(" · ", tickets)) + " (우클릭 → 그 등급 1회)");
        out.add("중복: " + shardItem + " (일반 " + shardAmount[0] + " · 고급 " + shardAmount[1] + " · 유일 " + shardAmount[2] + " · 전설 " + shardAmount[3] + "개)" + (ownsAll ? " · 전체 보유 테스트 모드 켜짐" : ""));
        CmdKit.lines(sender, out);
    }

    private void show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        ownership.owned(id, owned -> {
            if (owned == null) { CmdKit.fail(sender, "아티팩트 보유 정보를 불러오지 못했어요", null); return; }
            var out = new ArrayList<String>();
            out.add("── " + who + " 아티팩트 ──");
            out.add("보유 " + owned.size() + "개: " + (owned.isEmpty() ? "없음" : String.join(", ", owned.stream().sorted().map(this::label).toList())));
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add("장착: " + (equipped(p).isEmpty() ? "없음" : String.join(", ", equipped(p).stream().map(this::label).toList())));

                String cd = cooldownText(id);
                out.add("쿨타임: " + (cd.isEmpty() ? "없음" : cd.replace(";", " ").strip()));
            } else out.add("장착·쿨타임: 이 서버에 접속 중일 때만 보여요");
            CmdKit.lines(sender, out);
        });
    }

    private void own(CommandContext<CommandSourceStack> ctx, String id, boolean grant) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(target);
        plugin.getLogger().info("ARTIFACT ADMIN admin=" + sender.getName() + " player=" + target + " " + (id == null ? "reset" : (grant ? "grant " : "revoke ") + id));
        ownership.admin(target, id, grant, ok -> {
            if (!ok) { CmdKit.fail(sender, "아티팩트 보유를 바꾸지 못했어요 (공유 DB 확인)", null); return; }
            CmdKit.ok(sender, id == null ? who + " 님의 아티팩트 보유를 모두 지웠어요" : who + " 님" + (grant ? "에게 " : "의 ") + label(id) + "을(를) " + (grant ? "주었어요" : "회수했어요"));
        });
    }

    private void unequip(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        Player p = ChacaApi.targetOnline(ctx);
        if (!plugin.playerStateReady(p)) throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        var list = new ArrayList<>(equipped(p));
        String who = plugin.names().name(p);
        if (id == null) { if (list.isEmpty()) throw ChacaApi.error(who + " 님은 장착한 아티팩트가 없어요"); list.clear(); }
        else if (!list.remove(id)) throw ChacaApi.error(who + " 님은 " + label(id) + "을(를) 장착하지 않았어요");
        store(p, list); plugin.artifactsChanged(p); send(p, false, "");
        CmdKit.ok(CmdKit.sender(ctx), who + " 님 " + (id == null ? "아티팩트를 모두" : label(id) + "을(를)") + " 장착 해제했어요");
    }

    private void resetCooldowns(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = ChacaApi.targetOnline(ctx);
        readyAt.remove(p.getUniqueId());
        if (effects != null) effects.resetCooldowns(p);
        plugin.markPlayerStateDirty(p); send(p, false, "");
        CmdKit.ok(CmdKit.sender(ctx), plugin.names().name(p) + " 님 아티팩트 쿨타임을 초기화했어요");
    }

    private void returnKept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = ChacaApi.targetOnline(ctx);
        if (!plugin.playerStateReady(p)) throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        if (effects == null) throw ChacaApi.error("아티팩트 효과가 준비되지 않았어요");
        String who = plugin.names().name(p);
        String item = effects.life.adminReturnKept(p);
        if (item == null) throw ChacaApi.error(who + " 님에게 보관 중인 아이템이 없어요");
        CmdKit.ok(CmdKit.sender(ctx), who + " 님에게 보관품 " + item + "을(를) 돌려주었어요");
    }

    private void history(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        ownership.history(id, rows -> {
            if (rows == null) { CmdKit.fail(sender, "뽑기 기록을 불러오지 못했어요", null); return; }
            var out = new ArrayList<String>();
            out.add("── " + who + " 뽑기 중복 조각 기록 (" + rows.size() + "건) ──");
            for (int i = 0; i < rows.size(); i++) { var r = rows.get(i); out.add((i + 1) + ". " + r.item() + " ×" + r.amount() + " · " + (r.delivered() ? "우편 보냄" : "아직 못 보냄")); }
            if (rows.isEmpty()) out.add("(기록 없음)");
            else out.add("다시주기 <번호>: 우편을 한 번 더 보냄 · 완료처리 <번호>: 보낸 것으로 표시");
            CmdKit.lines(sender, out);
        });
    }

    private void fixHistory(CommandContext<CommandSourceStack> ctx, boolean resend) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        int number = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "번호");
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        ownership.history(id, rows -> {
            if (rows == null) { CmdKit.fail(sender, "뽑기 기록을 불러오지 못했어요", null); return; }
            if (number > rows.size()) { CmdKit.userFail(sender, "그 번호의 기록이 없어요 (뽑기기록 보기로 번호 확인)"); return; }
            var row = rows.get(number - 1);
            plugin.getLogger().info("ARTIFACT ADMIN history admin=" + sender.getName() + " player=" + id + " " + (resend ? "resend" : "mark") + " " + row);
            if (resend) ownership.redeliver(id, row, error -> { if (error == null) CmdKit.ok(sender, who + " 님에게 " + row.item() + " ×" + row.amount() + "을(를) 우편으로 다시 보냈어요"); else CmdKit.userFail(sender, error); });
            else if (row.delivered()) CmdKit.userFail(sender, "이미 우편을 보낸 기록이에요");
            else ownership.markDelivered(id, row, ok -> { if (ok == null) CmdKit.fail(sender, "기록을 바꾸지 못했어요", null); else CmdKit.ok(sender, who + " 님 " + number + "번 기록을 완료로 표시했어요"); });
        });
    }

    private void ownsAll(CommandContext<CommandSourceStack> ctx, boolean on) {
        ownsAll = on;
        for (Player p : Bukkit.getOnlinePlayers()) { if (plugin.playerStateReady(p)) plugin.artifactsChanged(p); if (listening.contains(p.getUniqueId())) send(p, false, ""); }
        CmdKit.ok(CmdKit.sender(ctx), "아티팩트 전체 보유 테스트 모드 " + (on ? "켜짐" : "꺼짐") + " (새로고침하면 artifacts.yml 값으로 돌아가요)");
    }
}
