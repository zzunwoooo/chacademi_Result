package school.magiccodex.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import school.magiccodex.database.DatabaseSettings;

/**
 * 일반 도메인(닉네임·친구·칭호·기숙사 배정/기부/점수·의뢰·대화·NPC 호감도·계절 공유)이 읽는 DB 설정 파일.
 * <ol>
 *   <li>{@code database.properties}가 있으면 그 파일 (예전과 같음).</li>
 *   <li>없고, {@code player-state-database.properties}에 {@code use-for-all-domains=true}가 있으면 그 파일 (한 파일로 전부 공유 DB로 전환).</li>
 *   <li>둘 다 아니면 {@code database.properties} (파일 없음 → SQLite, 예전과 같음).</li>
 * </ol>
 * 기본값은 꺼짐이라 키를 넣기 전에는 동작이 바뀌지 않는다. 키는 SQLite → MariaDB 이관(tools/db-migrate)을 마친 뒤에만 넣는다.
 */
final class GenericDatabaseFile {
    static final String GENERIC = "database.properties";
    static final String DEDICATED = "player-state-database.properties";
    static final String OPT_IN = "use-for-all-domains";
    /** 이 파일 설정을 따르는 도메인과 SQLite일 때의 로컬 파일 (시작 경고·상태 줄용). */
    static final List<String> DOMAINS = List.of("닉네임", "친구", "칭호", "기숙사 배정·기부·점수", "의뢰", "대화·스토리", "NPC 호감도", "계절 공유", "운영 기록", "청크 점유");
    static final String SQLITE_FILES = "friends.db, titles.db, school.db, quests.db, dialogue.db, affinity.db, audit.db, chunk-claims.db(야생)";

    private GenericDatabaseFile() {}

    static Path resolve(Path folder) {
        Path generic = folder.resolve(GENERIC);
        if (Files.exists(generic)) return generic;
        Path dedicated = folder.resolve(DEDICATED);
        return optedIn(dedicated) ? dedicated : generic;
    }

    /** 전용 파일에 use-for-all-domains=true 가 있는지. 읽지 못하면 false (그 파일 자체의 오류는 캐릭터 저장소 시작에서 따로 드러난다). */
    static boolean optedIn(Path dedicated) {
        if (!Files.exists(dedicated)) return false;
        var p = new Properties();
        try (var input = Files.newInputStream(dedicated)) { p.load(input); }
        catch (IOException e) { return false; }
        return Boolean.parseBoolean(p.getProperty(OPT_IN, "false").trim());
    }

    static DatabaseSettings load(Path folder) throws IOException {
        return DatabaseSettings.load(resolve(folder));
    }
}
