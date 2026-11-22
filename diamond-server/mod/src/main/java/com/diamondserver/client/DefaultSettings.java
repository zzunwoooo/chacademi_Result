package com.diamondserver.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오너가 정한 기본 설정을 이미 실행기를 깔아 둔 사람에게도 한 번만 넣는다.
 * (실행기는 설정 파일을 처음 한 번만 깔고 그 뒤로는 덮어쓰지 않으므로, 바뀐 기본값이 기존 설치에는 안 들어간다.)
 * 게임이 설정 파일을 읽기 전(preLaunch)에 고친다. 판마다 표시 파일을 남겨서, 그 뒤 유저가 직접 바꾼 값은 건드리지 않는다.
 *
 * 1판 (2026-11-22 오너): GUI 비율 3 · 채팅 배경 50% · 쉐이더 켬
 * 2판 (2026-11-23 오너): 기본 리소스팩 Ginko 판타지 GUI 켬 (실행기가 resourcepacks/ginko_fantasy_gui.zip 을 깔아 둠)
 */
public final class DefaultSettings implements PreLaunchEntrypoint {
    private static final String PACK = "file/ginko_fantasy_gui.zip";

    @Override
    public void onPreLaunch() {
        Path game = FabricLoader.getInstance().getGameDir();
        for (int v = 1; v <= 2; v++) apply(game, v);
    }

    private static void apply(Path game, int version) {
        Path mark = game.resolve("config").resolve("diamondserver").resolve("defaults-v" + version + ".txt");
        if (Files.exists(mark)) return;
        try {
            if (version == 2) addPack(game.resolve("options.txt"));
            else {
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("guiScale", "3");
            opts.put("textBackgroundOpacity", "0.5");
            set(game.resolve("options.txt"), ':', opts);
            set(game.resolve("config").resolve("iris.properties"), '=', Map.of("enableShaders", "true"));
            }
            Files.createDirectories(mark.getParent());
            Files.writeString(mark, "나니 다이아서버 기본 설정 " + version + "판을 넣었습니다. 이 파일을 지우면 다음 실행 때 다시 넣습니다.\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("[나니 다이아서버] 기본 설정을 넣지 못했습니다: " + e);
        }
    }

    /** options.txt 의 resourcePacks 목록 맨 뒤(가장 위에 덮이는 자리)에 기본 리소스팩을 넣는다. 이미 있으면 그대로 */
    private static void addPack(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        String entry = "\"" + PACK + "\"";
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("resourcePacks:")) continue;
            found = true;
            if (line.contains(entry)) return;
            int end = line.lastIndexOf(']');
            if (end < 0) return;
            String inside = line.substring(line.indexOf('[') + 1, end).trim();
            lines.set(i, "resourcePacks:[" + (inside.isEmpty() ? "" : inside + ",") + entry + "]");
        }
        if (!found) lines.add("resourcePacks:[\"vanilla\",\"fabric\"," + entry + "]");
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** key<sep>value 줄로 된 설정 파일에서 값을 바꾼다 (없는 키는 끝에 붙인다). 파일이 없으면 그대로 둔다 (처음 설치면 실행기가 깐 파일이 이미 새 값이다) */
    private static void set(Path file, char sep, Map<String, String> values) throws IOException {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        Map<String, String> left = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int at = line.indexOf(sep);
            if (at <= 0 || line.startsWith("#")) continue;
            String key = line.substring(0, at).trim();
            if (left.containsKey(key)) lines.set(i, key + sep + left.remove(key));
        }
        left.forEach((k, v) -> lines.add(k + sep + v));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
