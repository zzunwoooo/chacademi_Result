package kr.chacademy.cutscene.client;

import kr.chacademy.cutscene.data.CutsceneLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** 끝까지 본 컷신 목록. config/chaca_cutscene/_seen.txt 에 한 줄에 하나씩. 불러오기 작업 스레드에서도 읽으므로 synchronized. */
public final class SeenStore {
    private static Set<String> cache;

    private SeenStore() {}

    private static Path file() {
        return CutsceneLoader.rootDir().resolve("_seen.txt");
    }

    private static Set<String> ids() {
        if (cache == null) {
            cache = new LinkedHashSet<>();
            try {
                if (Files.isRegularFile(file())) {
                    for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) cache.add(line.trim());
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return cache;
    }

    public static synchronized boolean hasSeen(String id) {
        return ids().contains(id);
    }

    public static synchronized void markSeen(String id) {
        if (ids().add(id)) save();
    }

    public static synchronized void reset() {
        ids().clear();
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), ids(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
