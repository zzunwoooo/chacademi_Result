package kr.kkaegul.core.data;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 플레이어 데이터 저장소. 모든 플레이어 데이터를 메모리에 올려두고(서버 규모가 작음) 주기적으로 저장한다.
 * 랭킹(명예의 전당)을 위해 오프라인 플레이어 데이터도 시작 시 전부 읽는다.
 */
public final class DataStore {
    private final KkaegulCore plugin;
    private final File dir;
    private final Map<UUID, PlayerData> all = new ConcurrentHashMap<>();

    public DataStore(KkaegulCore plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "players");
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("players 폴더를 만들 수 없습니다");
        loadAll();
    }

    private void loadAll() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                PlayerData d = new PlayerData(id);
                d.load(YamlConfiguration.loadConfiguration(f));
                all.put(id, d);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("잘못된 데이터 파일 무시: " + f.getName());
            }
        }
        plugin.getLogger().info("플레이어 데이터 " + all.size() + "개 로드");
    }

    public PlayerData get(Player p) {
        PlayerData d = get(p.getUniqueId());
        if (!p.getName().equals(d.name)) { d.name = p.getName(); d.markDirty(); }
        return d;
    }

    public PlayerData get(UUID id) {
        return all.computeIfAbsent(id, k -> {
            PlayerData d = new PlayerData(k);
            OfflinePlayer op = Bukkit.getOfflinePlayer(k);
            if (op.getName() != null) d.name = op.getName();
            long lo = plugin.getConfig().getLong("diamond.starting-debt-min", plugin.getConfig().getLong("diamond.starting-debt", 0));
            long hi = plugin.getConfig().getLong("diamond.starting-debt-max", lo);
            d.debt = hi > lo ? lo + (long) (Math.random() * (hi - lo + 1)) : lo;   // 시작 다이아 랜덤 (min~max)
            d.gauge = plugin.getConfig().getInt("gauge.start", 50);
            d.markDirty();
            return d;
        });
    }

    /** 존재할 때만 */
    public PlayerData peek(UUID id) { return all.get(id); }

    /** 마인크래프트 이름 또는 한글 닉네임으로 찾기 */
    public PlayerData findByName(String name) {
        if (name == null) return null;
        for (PlayerData d : all.values()) if (d.name != null && d.name.equalsIgnoreCase(name)) return d;
        String n = kr.kkaegul.core.util.Txt.strip(kr.kkaegul.core.util.Txt.color(name));
        for (PlayerData d : all.values()) if (d.nickname != null && !d.nickname.isEmpty()
                && kr.kkaegul.core.util.Txt.strip(kr.kkaegul.core.util.Txt.color(d.nickname)).equalsIgnoreCase(n)) return d;
        return null;
    }

    /** 접속 중인 플레이어를 이름 또는 한글 닉네임으로 */
    public Player online(String name) {
        if (name == null) return null;
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        PlayerData d = findByName(name);
        return d == null ? null : Bukkit.getPlayer(d.uuid);
    }

    /** 자동완성용: 접속자 한글 닉네임 (영어 계정 이름은 닉네임이 아직 없을 때만) */
    public java.util.List<String> onlineNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = all.get(p.getUniqueId());
            String nick = d == null || d.nickname == null ? "" : kr.kkaegul.core.util.Txt.strip(kr.kkaegul.core.util.Txt.color(d.nickname));
            out.add(nick.isEmpty() ? p.getName() : nick);
        }
        return out;
    }

    public Collection<PlayerData> all() { return all.values(); }

    /** 메인 스레드에서 직렬화하고 디스크 쓰기는 비동기로 한다. */
    public void saveDirty(boolean async) {
        List<Object[]> jobs = new ArrayList<>();
        for (PlayerData d : all.values()) {
            if (!d.dirty) continue;
            YamlConfiguration y = new YamlConfiguration();
            d.save(y);
            d.dirty = false;
            jobs.add(new Object[]{y, new File(dir, d.uuid + ".yml")});
        }
        if (jobs.isEmpty()) return;
        Runnable write = () -> {
            for (Object[] j : jobs) {
                try {
                    ((YamlConfiguration) j[0]).save((File) j[1]);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "데이터 저장 실패: " + j[1], e);
                }
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }

    /** 이 사람 것만 바로 저장 (메인 스레드에서 직렬화, 쓰기는 비동기) */
    public void saveNow(PlayerData d) {
        YamlConfiguration y = new YamlConfiguration();
        d.save(y);
        d.dirty = false;
        File f = new File(dir, d.uuid + ".yml");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { y.save(f); } catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "데이터 저장 실패: " + f, e); }
        });
    }

    public void saveAll() {
        for (PlayerData d : all.values()) d.dirty = true;
        saveDirty(false);
    }
}
