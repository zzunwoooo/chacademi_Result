package school.chacademia.kyudichi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A09 (2026-10-21): rated results are written to plugins/ChacaKyudichi/outbox/&lt;matchId&gt;.yml BEFORE the DB write and the file
 * is deleted only after the DB answered "recorded" (or "already recorded" — {@link RatingStore#record} is idempotent per match ID).
 * A failed write is retried from the file at start-up and every minute, so a DB hiccup never loses a result and a retry never
 * applies it twice. The deltas were fixed at match end (rating before the match), so a replay gives the same numbers.
 */
final class ResultOutbox {
    /** Everything {@link RatingStore#record} needs. */
    record Entry(String mode, UUID match, String arena, int scoreA, int scoreB, int winner, String reason, RatingStore.Teams teams, List<RatingStore.Delta> deltas) {}

    private final Path dir; private final Logger log;
    ResultOutbox(Path dir, Logger log) { this.dir = dir; this.log = log; }

    Path file(UUID match) { return dir.resolve(match + ".yml"); }

    /** Writes the entry atomically (temp file → move). @return false when it could not be written (the DB write still goes ahead). */
    boolean save(Entry e) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(e.match() + ".yml.tmp");
            Files.writeString(tmp, encode(e), StandardCharsets.UTF_8);
            try { Files.move(tmp, file(e.match()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException x) { Files.move(tmp, file(e.match()), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException | RuntimeException x) {
            log.severe("큐디치 결과 outbox 저장 실패 (" + e.match() + "): " + x);
            return false;
        }
    }

    void delete(UUID match) {
        try { Files.deleteIfExists(file(match)); } catch (IOException e) { log.warning("큐디치 결과 outbox 삭제 실패 (" + match + "): " + e); }
    }

    /** Every stored entry (unreadable files are logged and left alone for an admin). */
    List<Entry> all() {
        var out = new ArrayList<Entry>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                try { out.add(decode(Files.readString(f, StandardCharsets.UTF_8))); }
                catch (IOException | InvalidConfigurationException | RuntimeException e) { log.severe("큐디치 결과 outbox 파일을 읽지 못함 " + f.getFileName() + ": " + e); }
            }
        } catch (IOException e) { log.warning("큐디치 결과 outbox 목록 실패: " + e); }
        return out;
    }

    static String encode(Entry e) {
        var y = new YamlConfiguration();
        y.set("mode", e.mode()); y.set("match", e.match().toString()); y.set("arena", e.arena());
        y.set("score-a", e.scoreA()); y.set("score-b", e.scoreB()); y.set("winner", e.winner()); y.set("reason", e.reason());
        y.set("teams.name-a", e.teams().nameA()); y.set("teams.name-b", e.teams().nameB());
        y.set("teams.house-a", e.teams().houseA()); y.set("teams.house-b", e.teams().houseB());
        int i = 0;
        for (var d : e.deltas()) {
            String k = "deltas." + i++ + ".";
            y.set(k + "id", d.id().toString()); y.set(k + "name", d.name()); y.set(k + "rating", d.rating());
            y.set(k + "win", d.win()); y.set(k + "draw", d.draw()); y.set(k + "loss", d.loss()); y.set(k + "leaver", d.leaver());
            y.set(k + "goals", d.goals()); y.set(k + "kills", d.kills()); y.set(k + "deaths", d.deaths()); y.set(k + "assists", d.assists());
            y.set(k + "damage", d.damage()); y.set(k + "heal", d.heal()); y.set(k + "snitch", d.snitch()); y.set(k + "deflect-hits", d.deflectHits());
            y.set(k + "mvp", d.mvp()); y.set(k + "team", d.team()); y.set(k + "delta", d.delta());
        }
        return y.saveToString();
    }

    static Entry decode(String text) throws InvalidConfigurationException {
        var y = new YamlConfiguration();
        y.loadFromString(text);
        var deltas = new ArrayList<RatingStore.Delta>();
        var sec = y.getConfigurationSection("deltas");
        if (sec != null) for (String key : sec.getKeys(false)) {
            String k = "deltas." + key + ".";
            deltas.add(new RatingStore.Delta(UUID.fromString(y.getString(k + "id", "")), y.getString(k + "name", ""), y.getInt(k + "rating"),
                    y.getBoolean(k + "win"), y.getBoolean(k + "draw"), y.getBoolean(k + "loss"), y.getBoolean(k + "leaver"),
                    y.getInt(k + "goals"), y.getInt(k + "kills"), y.getInt(k + "deaths"), y.getInt(k + "assists"),
                    y.getDouble(k + "damage"), y.getDouble(k + "heal"), y.getInt(k + "snitch"), y.getInt(k + "deflect-hits"),
                    y.getBoolean(k + "mvp"), y.getInt(k + "team"), y.getInt(k + "delta")));
        }
        if (deltas.isEmpty()) throw new InvalidConfigurationException("deltas 없음");
        return new Entry(y.getString("mode", RatingStore.MODE), UUID.fromString(y.getString("match", "")), y.getString("arena", ""),
                y.getInt("score-a"), y.getInt("score-b"), y.getInt("winner", -1), y.getString("reason", ""),
                new RatingStore.Teams(y.getString("teams.name-a", ""), y.getString("teams.name-b", ""), y.getInt("teams.house-a", -1), y.getInt("teams.house-b", -1)),
                deltas);
    }
}
