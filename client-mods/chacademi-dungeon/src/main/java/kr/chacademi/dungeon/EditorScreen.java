package kr.chacademi.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 던전 설정 창 (설정 도구로 허공 우클릭). 명령어 없이 버튼·입력칸만으로:
 * 칸·구역(시작점·칸 구역·방·문·몹 구역·보스·지점·칸 복사) / 목록(지우기·문과 기믹 연결) / 단계 / 보상·기타 / 기믹(설치·조절).
 * 내용은 서버가 보낸 JSON(EditorState.editor), 버튼은 서버에 동작만 보낸다. 서버가 처리하면 새 내용을 보내 창이 다시 그려진다.
 */
final class EditorScreen extends Screen {
    private static final int GOLD = 0xFFFFE08A, MUTED = 0xFFAAB4C2, WHITE = 0xFFFFFFFF, OK = 0xFF8EF0A0, BAD = 0xFFFF9A9A;
    private static final String[] TABS = {"칸·구역", "목록", "단계", "보상·기타", "기믹"};
    private static final String[] STAGE_TYPES = {"room", "kill", "boss", "gimmick", "reach"};
    private static final String[] STAGE_LABELS = {"방 정리", "몹 처치", "보스 처치", "기믹 열기", "지점 도착"};

    // 창을 다시 그려도 남는 값 (입력 중이던 것)
    private static int tab, listPage, modelPage, stageIndex = -1, stageType, roomIndex;
    private static String name = "", count = "5", chance = "10", radius = "2.5", mob = "", bossMob = "";
    private static String stageName = "", stageGoal = "", stageTarget = "", stageCount = "10";
    private static final Set<String> stageDoors = new LinkedHashSet<>();
    private static boolean copyGimmicks = true;
    private String armed = "";

    private int left, top, w, h;
    private EditBox nameBox, countBox, chanceBox, radiusBox, sName, sGoal, sCount;

    EditorScreen() { super(Component.literal("던전 설정")); }

    @Override public boolean isPauseScreen() { return false; }

    void refresh() { saveBoxes(); rebuildWidgets(); }

    private JsonObject ed() { return EditorState.editor; }
    private String s(String k) { return EditorState.str(ed(), k); }
    private JsonArray arr(String k) { return ed().has(k) && ed().get(k).isJsonArray() ? ed().getAsJsonArray(k) : new JsonArray(); }
    private List<String> strings(String k) { var out = new ArrayList<String>(); for (var e : arr(k)) out.add(e.isJsonPrimitive() ? e.getAsString() : EditorState.str(e.getAsJsonObject(), "name")); return out; }

    private void saveBoxes() {
        if (nameBox != null) name = nameBox.getValue();
        if (countBox != null) count = countBox.getValue();
        if (chanceBox != null) chance = chanceBox.getValue();
        if (radiusBox != null) radius = radiusBox.getValue();
        if (sName != null) stageName = sName.getValue();
        if (sGoal != null) stageGoal = sGoal.getValue();
        if (sCount != null) stageCount = sCount.getValue();
        nameBox = countBox = chanceBox = radiusBox = sName = sGoal = sCount = null;
    }

    @Override public void onClose() { saveBoxes(); super.onClose(); }

    // ------------------------------------------------------------ 배치

    @Override protected void init() {
        w = Math.min(460, width - 16); h = Math.min(272, height - 16);
        left = (width - w) / 2; top = (height - h) / 2;
        int tx = left + 8;
        for (int i = 0; i < TABS.length; i++) {
            int k = i;
            var b = Button.builder(Component.literal((tab == i ? "▶ " : "") + TABS[i]), x -> { saveBoxes(); tab = k; rebuildWidgets(); }).bounds(tx, top + 22, 72, 16).build();
            b.active = tab != i;
            addRenderableWidget(b);
            tx += 74;
        }
        addRenderableWidget(Button.builder(Component.literal("✕"), x -> onClose()).bounds(left + w - 22, top + 4, 16, 16).build());
        int y = top + 44;
        switch (tab) {
            case 0 -> initArea(y);
            case 1 -> initList(y);
            case 2 -> initStages(y);
            case 3 -> initMisc(y);
            default -> initGimmick(y);
        }
    }

    private Button button(String label, int x, int y, int bw, Runnable action) {
        var b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, bw, 18).build();
        addRenderableWidget(b);
        return b;
    }

    private Button tip(Button b, String tip) { b.setTooltip(Tooltip.create(Component.literal(tip))); return b; }

    private EditBox box(int x, int y, int bw, String value, String hint, int max) {
        var e = new EditBox(font, x, y, bw, 18, Component.literal(hint));
        e.setMaxLength(max);
        e.setValue(value);
        e.setHint(Component.literal(hint).withColor(0x8090A0));
        addRenderableWidget(e);
        return e;
    }

    private String nameValue() { return nameBox == null ? name : nameBox.getValue().trim(); }

    /** 같은 버튼을 3초 안에 두 번 눌러야 하는 위험한 동작 (비우기·지우기). */
    private boolean confirm(String key) {
        if (armed.equals(key)) { armed = ""; return true; }
        armed = key;
        EditorState.editor.addProperty("msg", "!한 번 더 누르면 실행돼요");
        return false;
    }

    // ---- 칸·구역

    private void initArea(int y) {
        int x = left + 10, col2 = left + w / 2 + 4, bw = w / 2 - 16;
        // 던전·칸
        var ds = arr("dungeons");
        String cur = s("dungeon");
        int slots = 3;
        for (var e : ds) if (EditorState.str(e.getAsJsonObject(), "id").equals(cur)) slots = EditorState.num(e.getAsJsonObject(), "slots", 3);
        if (ds.size() > 1) button("던전 바꾸기", x, y, 80, () -> {
            int i = 0;
            for (int k = 0; k < ds.size(); k++) if (EditorState.str(ds.get(k).getAsJsonObject(), "id").equals(cur)) i = k;
            EditorState.send("slot", "dungeon", EditorState.str(ds.get((i + 1) % ds.size()).getAsJsonObject(), "id"), "slot", 1);
        });
        int sx = x + (ds.size() > 1 ? 86 : 0);
        for (int no = 1; no <= slots; no++) {
            int n = no;
            var b = button(n + "번 칸", sx, y, 52, () -> EditorState.send("slot", "dungeon", cur, "slot", n));
            b.active = EditorState.num(ed(), "slot", 1) != n;
            sx += 56;
        }
        y += 34;
        tip(button("시작점 = 지금 내 자리", x, y, bw, () -> EditorState.send("start.set")), "판이 시작되면 이 자리·방향으로 옮겨져요");
        tip(button("고른 구역 → 칸 전체 구역", col2, y, bw, () -> EditorState.send("region.set")), "끝나면 이 안의 몹·떨어진 아이템·기믹을 정리해요");
        y += 22;
        nameBox = box(x, y, bw, name, "이름 (예: 1번방, 1-1 door)", 32);
        tip(button("고른 구역 → 방", col2, y, bw / 2 - 2, () -> EditorState.send("room.add", "name", nameValue())), "방에 들어가면 그 방의 몹 구역에서 몹이 나와요");
        tip(button("고른 구역 → 문", col2 + bw / 2 + 2, y, bw / 2 - 2, () -> EditorState.send("door.add", "name", nameValue())),
                "판이 시작되면 닫혀 있는 문 (보라 홀로그램). 단계 설정에서 '끝나면 열 문'으로 골라요");
        y += 26;
        // 몹 구역
        var rooms = strings("rooms");
        if (roomIndex >= rooms.size()) roomIndex = 0;
        String room = rooms.isEmpty() ? "" : rooms.get(roomIndex);
        tip(button("방: " + (room.isEmpty() ? "(방 먼저)" : room), x, y, bw / 2 - 2, () -> { if (!rooms.isEmpty()) { saveBoxes(); roomIndex = (roomIndex + 1) % rooms.size(); rebuildWidgets(); } }), "눌러서 다음 방");
        tip(button("몹: " + (mob.isEmpty() ? "고르기" : mob), x + bw / 2 + 2, y, bw / 2 - 2, () -> pickMob(m -> mob = m)), "MythicMobs 몹 목록에서 고르기");
        countBox = box(col2, y, 40, count, "수", 3);
        tip(button("고른 구역 → 몹 구역", col2 + 44, y, bw - 44, () -> EditorState.send("zone.add", "room", room, "mob", mob, "count", parse(countBox.getValue(), 1))),
                "구역은 땅 한 칸 위로 잡아 주세요. 안전한 땅에서 이 수만큼 무작위로 나와요");
        y += 26;
        // 보스·지점
        tip(button("보스 몹: " + (bossMob.isEmpty() ? "고르기" : bossMob), x, y, bw, () -> pickMob(m -> bossMob = m)), "보스 단계에서 소환할 몹");
        tip(button("보스 자리 = 지금 자리 (이름 칸)", col2, y, bw, () -> EditorState.send("boss.set", "name", nameValue(), "mob", bossMob)), "이름 칸에 보스 이름(예: lich)");
        y += 22;
        tip(button("지점 = 지금 자리 (이름 칸)", x, y, bw, () -> EditorState.send("point.set", "name", nameValue())), "'지점 도착' 단계에서 써요");
        button("고른 구역 지우기", col2, y, bw, () -> EditorState.send("sel.clear"));
        y += 26;
        // 칸 복사
        int cx = x;
        int mine = EditorState.num(ed(), "slot", 1);
        for (int no = 1; no <= slots; no++) {
            if (no == mine) continue;
            int n = no;
            tip(button(mine + "번 → " + n + "번 칸 복사", cx, y, 110, () -> EditorState.send("copy", "to", n, "gimmicks", copyGimmicks)),
                    n + "번 칸 시작점을 먼저 찍어 두면, 그 차이만큼 옮겨서 방·문·몹 구역·보스·지점을 복사해요");
            cx += 114;
        }
        button("기믹도 복사: " + (copyGimmicks ? "예" : "아니오"), cx, y, 100, () -> { saveBoxes(); copyGimmicks = !copyGimmicks; rebuildWidgets(); });
        tip(button("이 칸 비우기", left + w - 90, y, 80, () -> { if (confirm("clear")) EditorState.send("slot.clear"); }), "두 번 누르면 이 칸 설정을 전부 지워요");
    }

    // ---- 목록

    private record Row(String label, String action, String key, String extra) {}

    private void initList(int y) {
        var rows = new ArrayList<Row>();
        for (var e : arr("rooms")) {
            var r = e.getAsJsonObject();
            String n = EditorState.str(r, "name");
            rows.add(new Row("방 " + n, "room.del", n, ""));
            var zs = r.getAsJsonArray("zones");
            for (int i = 0; zs != null && i < zs.size(); i++) {
                var z = zs.get(i).getAsJsonObject();
                rows.add(new Row("   └ 몹 구역 " + EditorState.str(z, "mob") + " ×" + EditorState.num(z, "count", 1), "zone.del", n, "" + i));
            }
        }
        for (var e : arr("doors")) {
            var d = e.getAsJsonObject();
            String g = EditorState.str(d, "gimmick");
            rows.add(new Row("문 " + EditorState.str(d, "name") + (g.isEmpty() ? "" : "  · 기믹 " + g + " 열리면 열림"), "door.del", EditorState.str(d, "name"), "door"));
        }
        for (String n : strings("bosses")) rows.add(new Row("보스 " + n, "boss.del", n, ""));
        for (String n : strings("points")) rows.add(new Row("지점 " + n, "point.del", n, ""));
        for (String n : strings("gimmicks")) rows.add(new Row("기믹 이름 " + n, "gimmick.unname", n, ""));
        int per = Math.max(4, (h - 80) / 20), pages = Math.max(1, (rows.size() + per - 1) / per);
        listPage = Math.min(listPage, pages - 1);
        var gimmicks = strings("gimmicks");
        for (int i = listPage * per; i < Math.min(rows.size(), (listPage + 1) * per); i++) {
            var r = rows.get(i);
            int ry = y + (i - listPage * per) * 20;
            String key = r.action() + ":" + r.key() + ":" + r.extra();
            button("지움", left + w - 52, ry, 42, () -> {
                if (!confirm(key)) return;
                if (r.action().equals("zone.del")) EditorState.send("zone.del", "room", r.key(), "index", parse(r.extra(), 0));
                else EditorState.send(r.action(), "name", r.key());
            });
            if (r.extra().equals("door") && !gimmicks.isEmpty())
                tip(button("기믹 연결", left + w - 128, ry, 72, () -> {
                    // 다음 기믹 이름으로 (마지막 다음은 "연결 안 함")
                    String now = "";
                    for (var e : arr("doors")) if (EditorState.str(e.getAsJsonObject(), "name").equals(r.key())) now = EditorState.str(e.getAsJsonObject(), "gimmick");
                    int idx = gimmicks.indexOf(now);
                    String next = idx + 1 < gimmicks.size() ? gimmicks.get(idx + 1) : "";
                    EditorState.send("door.link", "name", r.key(), "gimmick", next);
                }), "누를 때마다 다음 기믹으로 (끝까지 가면 연결 안 함). 그 기믹이 열리면 문도 열려요");
        }
        if (pages > 1) {
            button("◀", left + 10, top + h - 26, 20, () -> { saveBoxes(); listPage = Math.max(0, listPage - 1); rebuildWidgets(); });
            button("▶", left + 34, top + h - 26, 20, () -> { saveBoxes(); listPage = Math.min(pages - 1, listPage + 1); rebuildWidgets(); });
        }
        listRows = rows; listPer = per;
    }
    private List<Row> listRows = List.of();
    private int listPer = 1;

    // ---- 단계

    private void initStages(int y) {
        var stages = arr("stages");
        int x = left + 10, listW = 170;
        for (int i = 0; i < stages.size() && i < 9; i++) {
            var st = stages.get(i).getAsJsonObject();
            int k = i;
            String type = EditorState.str(st, "type");
            int ti = List.of(STAGE_TYPES).indexOf(type);
            var b = button((i + 1) + ". " + EditorState.str(st, "name") + " (" + (ti >= 0 ? STAGE_LABELS[ti] : type) + ")", x, y + i * 20, listW - 44, () -> {
                saveBoxes();
                stageIndex = k;
                stageName = EditorState.str(st, "name"); stageGoal = EditorState.str(st, "goal");
                stageType = Math.max(0, List.of(STAGE_TYPES).indexOf(EditorState.str(st, "type")));
                stageTarget = EditorState.str(st, "target"); stageCount = "" + EditorState.num(st, "count", 1);
                stageDoors.clear();
                for (var d : st.getAsJsonArray("doors")) stageDoors.add(d.getAsString());
                rebuildWidgets();
            });
            b.active = stageIndex != i;
            button("▲", x + listW - 42, y + i * 20, 20, () -> EditorState.send("stage.move", "index", k, "dir", -1));
            button("▼", x + listW - 20, y + i * 20, 20, () -> EditorState.send("stage.move", "index", k, "dir", 1));
        }
        int ny = y + Math.min(9, stages.size()) * 20 + 4;
        button("+ 새 단계", x, ny, listW, () -> { saveBoxes(); stageIndex = -1; stageName = ""; stageGoal = ""; stageTarget = ""; stageCount = "10"; stageDoors.clear(); rebuildWidgets(); });

        // 고치는 칸
        int fx = left + listW + 22, fw = w - listW - 32, fy = y;
        sName = box(fx, fy, fw, stageName, "단계 이름 (단계창 제목)", 40); fy += 22;
        sGoal = box(fx, fy, fw, stageGoal, "목표 문구 (예: 입구의 언데드를 모두 처치하세요)", 80); fy += 22;
        button("종류: " + STAGE_LABELS[stageType], fx, fy, fw / 2 - 2, () -> { saveBoxes(); stageType = (stageType + 1) % STAGE_TYPES.length; stageTarget = ""; rebuildWidgets(); });
        List<String> targets = switch (STAGE_TYPES[stageType]) {
            case "room" -> strings("rooms");
            case "boss" -> strings("bosses");
            case "gimmick" -> strings("gimmicks");
            case "reach" -> strings("points");
            default -> List.of();
        };
        if (STAGE_TYPES[stageType].equals("kill"))
            button("몹: " + (stageTarget.isEmpty() ? "고르기 (* = 아무 몹)" : stageTarget), fx + fw / 2 + 2, fy, fw / 2 - 2, () -> pickMob(m -> stageTarget = m));
        else
            tip(button("대상: " + (stageTarget.isEmpty() ? (targets.isEmpty() ? "(먼저 만들기)" : "고르기") : stageTarget), fx + fw / 2 + 2, fy, fw / 2 - 2, () -> {
                if (targets.isEmpty()) return;
                saveBoxes();
                int i = targets.indexOf(stageTarget);
                stageTarget = targets.get((i + 1) % targets.size());
                rebuildWidgets();
            }), "눌러서 다음");
        fy += 22;
        if (STAGE_TYPES[stageType].equals("kill")) { sCount = box(fx, fy, 60, stageCount, "마리 수", 4); fy += 22; }
        // 끝나면 열 문 (눌러서 켜고 끄기)
        int dx = fx;
        for (String door : strings("doors")) {
            boolean on = stageDoors.contains(door);
            String label = (on ? "■ " : "□ ") + door;
            int bw = Math.min(fw, font.width(label) + 14);
            if (dx + bw > fx + fw) { dx = fx; fy += 20; }
            tip(button(label, dx, fy, bw, () -> { saveBoxes(); if (!stageDoors.remove(door)) stageDoors.add(door); rebuildWidgets(); }), "이 단계가 끝나면 열 문");
            dx += bw + 4;
        }
        fy += 24;
        button(stageIndex >= 0 ? (stageIndex + 1) + "단계 저장" : "새 단계 추가", fx, fy, fw / 2 - 2, () -> {
            saveBoxes();
            var doors = new JsonArray(); stageDoors.forEach(doors::add);
            EditorState.send("stage.save", "index", stageIndex, "name", stageName, "goal", stageGoal, "type", STAGE_TYPES[stageType],
                    "target", stageTarget, "count", parse(stageCount, 1), "doors", doors);
        });
        if (stageIndex >= 0) button("이 단계 지우기", fx + fw / 2 + 2, fy, fw / 2 - 2, () -> {
            if (!confirm("stage" + stageIndex)) return;
            EditorState.send("stage.del", "index", stageIndex);
            stageIndex = -1;
        });
    }

    // ---- 보상·기타

    private void initMisc(int y) {
        int x = left + 10, bw = (w - 30) / 3;
        tip(button("보조손 것 → 기본 보상", x, y, bw, () -> EditorState.send("reward.add", "kind", "base")), "클리어할 때마다. 넣을 아이템은 보조손(F키)에 들고 창을 여세요");
        tip(button("보조손 것 → 첫 클리어", x + bw + 5, y, bw, () -> EditorState.send("reward.add", "kind", "first")), "처음 클리어할 때 한 번 더");
        chanceBox = box(x + (bw + 5) * 2, y, 40, chance, "%", 6);
        tip(button("→ 확률 보상", x + (bw + 5) * 2 + 44, y, bw - 44, () -> EditorState.send("reward.add", "kind", "random", "chance", parseD(chanceBox.getValue(), 0))), "클리어할 때마다 이 확률로");
        y += 22;
        button("기본 비우기", x, y, bw, () -> { if (confirm("rb")) EditorState.send("reward.clear", "kind", "base"); });
        button("첫 클리어 비우기", x + bw + 5, y, bw, () -> { if (confirm("rf")) EditorState.send("reward.clear", "kind", "first"); });
        button("확률 비우기", x + (bw + 5) * 2, y, bw, () -> { if (confirm("rr")) EditorState.send("reward.clear", "kind", "random"); });
        y += 30;
        radiusBox = box(x, y, 40, radius, "반경", 4);
        tip(button("입장 포탈 = 지금 자리", x + 44, y, bw - 44, () -> EditorState.send("portal.set", "radius", parseD(radiusBox.getValue(), 2.5))), "학교 쪽 포탈 자리. 반경 안에 들어오면 입장창");
        tip(button("나가는 곳 = 지금 자리", x + bw + 5, y, bw, () -> EditorState.send("exit.set")), "끝나거나 실패하면 돌아가는 곳");
        boolean regen = ed().has("regen") && ed().get("regen").getAsBoolean();
        tip(button("체력 자동 회복: " + (regen ? "켜짐" : "꺼짐"), x + (bw + 5) * 2, y, bw, () -> EditorState.send("regen", "on", !regen)), "던전 안 배고픔 회복 (마나·물약·마법은 상관없음)");
        y += 22;
        button("설정 도구 하나 더 받기", x, y, bw, () -> EditorState.send("tool"));
    }

    // ---- 기믹

    private void initGimmick(int y) {
        int x = left + 10;
        if (!(ed().has("gimmickReady") && ed().get("gimmickReady").getAsBoolean())) return;
        if (ed().has("gimmick")) {
            var g = ed().getAsJsonObject("gimmick");
            int bx = x, by = y + 40, bw = 62;
            button("회전 ◀", bx, by, bw, () -> edit("rotate", -1)); button("회전 ▶", bx + 66, by, bw, () -> edit("rotate", 1));
            button("높이 ▼", bx + 140, by, bw, () -> edit("height", -1)); button("높이 ▲", bx + 206, by, bw, () -> edit("height", 1));
            by += 22;
            button("앞으로", bx, by, bw, () -> moveG("f")); button("뒤로", bx + 66, by, bw, () -> moveG("b"));
            button("왼쪽", bx + 140, by, bw, () -> moveG("l")); button("오른쪽", bx + 206, by, bw, () -> moveG("r"));
            by += 22;
            button("크기 −", bx, by, bw, () -> edit("scale", -1)); button("크기 +", bx + 66, by, bw, () -> edit("scale", 1));
            if (!EditorState.str(g, "answer").isEmpty()) { button("정답 ◀", bx + 140, by, bw, () -> edit("answer", -1)); button("정답 ▶", bx + 206, by, bw, () -> edit("answer", 1)); }
            by += 28;
            nameBox = box(bx, by, 132, name, "이름 (예: 불꽃화로1)", 32);
            tip(button("이 기믹에 이름 붙이기", bx + 140, by, 128, () -> EditorState.send("gimmick.name", "name", nameValue())), "이름을 붙여야 '기믹 열기' 단계나 문 연결에 쓸 수 있어요");
            by += 28;
            tip(button("삭제", bx, by, 62, () -> { if (confirm("gdel")) edit("delete", 1); }), "두 번 누르면 삭제");
            button("모델 목록 보기", bx + 66, by, 110, () -> EditorState.send("gimmick.close"));
            return;
        }
        var models = arr("models");
        int cols = 3, per = cols * Math.max(3, (h - 90) / 20), pages = Math.max(1, (models.size() + per - 1) / per);
        modelPage = Math.min(modelPage, pages - 1);
        int cw = (w - 20 - (cols - 1) * 4) / cols;
        for (int i = modelPage * per; i < Math.min(models.size(), (modelPage + 1) * per); i++) {
            var m = models.get(i).getAsJsonArray();
            int k = i - modelPage * per;
            String id = m.get(0).getAsString(), nm = m.get(1).getAsString(), role = m.get(2).getAsString();
            int fw = parse(m.get(3).getAsString(), 1), fd = parse(m.get(4).getAsString(), 1), fh = parse(m.get(5).getAsString(), 1);
            tip(button(nm + " · " + role, x + (k % cols) * (cw + 4), y + 16 + (k / cols) * 20, cw, () -> {
                EditorState.placeModel = id; EditorState.placeName = nm;
                EditorState.placeW = fw; EditorState.placeD = fd; EditorState.placeH = fh;
                var mc = Minecraft.getInstance();
                EditorState.placeFacing = mc.player == null ? 0 : Math.floorMod(Math.round(mc.player.getYRot() / 90f), 4);
                onClose();
            }), id + " · " + fw + "×" + fd + "칸, 높이 " + fh + "\n누르면 보는 자리에 미리보기 → 우클릭으로 설치");
        }
        if (pages > 1) {
            button("◀", left + 10, top + h - 26, 20, () -> { saveBoxes(); modelPage = Math.max(0, modelPage - 1); rebuildWidgets(); });
            button("▶", left + 34, top + h - 26, 20, () -> { saveBoxes(); modelPage = Math.min(pages - 1, modelPage + 1); rebuildWidgets(); });
        }
    }

    private void edit(String op, int sign) { EditorState.send("gimmick.edit", "op", op, "sign", sign); }
    private void moveG(String dir) { EditorState.send("gimmick.edit", "op", "move", "dir", dir, "sign", 1); }

    private void pickMob(Consumer<String> set) {
        saveBoxes();
        var mobs = new ArrayList<String>();
        for (var e : arr("mobs")) mobs.add(e.getAsString());
        Minecraft.getInstance().setScreen(new MobPicker(this, mobs, m -> { set.accept(m); }));
    }

    private static int parse(String v, int def) { try { return Integer.parseInt(v.trim()); } catch (RuntimeException e) { return def; } }
    private static double parseD(String v, double def) { try { return Double.parseDouble(v.trim()); } catch (RuntimeException e) { return def; } }

    // ------------------------------------------------------------ 그리기

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        Art.ui(g, "panels/screen-dimmer", 0, 0, width, height, 0xCCFFFFFF);
        Art.ui(g, "panels/panel-fill", left, top, w, h, 0xFFFFFFFF);
        Art.nine(g, "panels/modal-panel", left - 4, top - 4, w + 8, h + 8, 28, 9, 0xFFFFFFFF);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        var dsName = "";
        for (var e : arr("dungeons")) if (EditorState.str(e.getAsJsonObject(), "id").equals(s("dungeon"))) dsName = EditorState.str(e.getAsJsonObject(), "name");
        g.drawString(font, "던전 설정 · " + dsName + " " + EditorState.num(ed(), "slot", 1) + "번 칸", left + 10, top + 8, GOLD);
        int x = left + 10;
        switch (tab) {
            case 0 -> {
                g.drawString(font, "고른 구역: " + (ed().has("hasSel") && ed().get("hasSel").getAsBoolean() ? s("selSize") : "없음 — 도구로 블록 좌클릭·우클릭"), x, top + 66, MUTED);
                g.drawString(font, "시작점: " + (s("start").isEmpty() ? "없음" : s("start")) + "   칸 구역: " + (s("region").isEmpty() ? "없음" : s("region")), x, top + 205 > top + h - 40 ? top + h - 40 : top + 205, MUTED);
            }
            case 1 -> {
                if (listRows.isEmpty()) g.drawString(font, "아직 만든 것이 없어요. '칸·구역'에서 구역을 골라 방·문을 만들어 주세요.", x, top + 50, MUTED);
                int i0 = listPage * listPer;
                for (int i = i0; i < Math.min(listRows.size(), i0 + listPer); i++) g.drawString(font, listRows.get(i).label(), x, top + 49 + (i - i0) * 20, WHITE);
            }
            case 2 -> { if (arr("stages").isEmpty()) g.drawString(font, "단계가 없어요. 오른쪽에서 새 단계를 추가하세요.", x, top + 46, MUTED); }
            case 3 -> {
                int y = top + 122;
                g.drawString(font, "보상: 기본 " + EditorState.num(ed(), "rewardBase", 0) + "줄 · 첫 클리어 " + EditorState.num(ed(), "rewardFirst", 0) + "줄 · 확률 " + EditorState.num(ed(), "rewardRandom", 0) + "줄", x, y, WHITE);
                g.drawString(font, "입장 포탈: " + (s("portal").isEmpty() ? "없음" : s("portal")) + "   나가는 곳: " + (s("exit").isEmpty() ? "포탈 앞" : s("exit")), x, y + 12, MUTED);
                y += 30;
                g.drawString(font, "점검", x, y, GOLD);
                for (String line : strings("check")) { y += 11; g.drawString(font, line, x, y, line.endsWith("준비됨") ? OK : WHITE); }
            }
            default -> {
                if (!(ed().has("gimmickReady") && ed().get("gimmickReady").getAsBoolean()))
                    g.drawString(font, "기믹 플러그인(ChacaGimmick)에 설치 연결이 없어요. 서버에 새 기믹 jar 가 필요해요.", x, top + 50, BAD);
                else if (ed().has("gimmick")) {
                    var gm = ed().getAsJsonObject("gimmick");
                    g.drawString(font, EditorState.str(gm, "name") + " (" + EditorState.str(gm, "model") + ") · " + EditorState.str(gm, "role"), x, top + 48, GOLD);
                    g.drawString(font, "방향 " + EditorState.str(gm, "facing") + " · 크기 " + EditorState.str(gm, "scale") + " · 블록 " + EditorState.str(gm, "x") + ", " + EditorState.str(gm, "y") + ", " + EditorState.str(gm, "z")
                            + (EditorState.str(gm, "answer").isEmpty() ? "" : " · 정답 " + EditorState.str(gm, "answer"))
                            + (EditorState.str(gm, "linked").isEmpty() ? "" : " · 이름 " + EditorState.str(gm, "linked")), x, top + 60, WHITE);
                } else g.drawString(font, "설치할 기믹을 고르세요. 고르면 보는 자리에 미리보기가 나오고, 우클릭으로 설치해요. 설치한 기믹은 도구로 우클릭하면 조절 창.", x, top + 46, MUTED);
            }
        }
        String msg = s("msg");
        if (!msg.isEmpty()) {
            boolean bad = msg.startsWith("!");
            g.drawString(font, bad ? msg.substring(1) : msg, left + 64, top + h - 21, bad ? BAD : OK);
        }
    }

    // ------------------------------------------------------------ 몹 고르기

    static final class MobPicker extends Screen {
        private final Screen parent;
        private final List<String> all;
        private final Consumer<String> done;
        private EditBox search;
        private int page;
        private String query = "";

        MobPicker(Screen parent, List<String> all, Consumer<String> done) { super(Component.literal("몹 고르기")); this.parent = parent; this.all = all; this.done = done; }
        @Override public boolean isPauseScreen() { return false; }

        @Override protected void init() {
            int w = Math.min(420, width - 16), h = Math.min(260, height - 16), left = (width - w) / 2, top = (height - h) / 2;
            search = new EditBox(font, left + 10, top + 8, w - 110, 18, Component.literal("검색"));
            search.setValue(query);
            search.setHint(Component.literal("검색 (예: Archlich)").withColor(0x8090A0));
            search.setResponder(v -> { if (!v.equals(query)) { query = v; page = 0; rebuildWidgets(); setFocused(search); search.setFocused(true); } });
            addRenderableWidget(search);
            addRenderableWidget(Button.builder(Component.literal("* 아무 몹"), b -> pick("*")).bounds(left + w - 96, top + 8, 86, 18).build());
            var list = all.stream().filter(m -> query.isBlank() || m.toLowerCase().contains(query.toLowerCase())).toList();
            int cols = 2, rows = Math.max(3, (h - 64) / 20), per = cols * rows, pages = Math.max(1, (list.size() + per - 1) / per);
            page = Math.min(page, pages - 1);
            int cw = (w - 24) / cols;
            for (int i = page * per; i < Math.min(list.size(), (page + 1) * per); i++) {
                int k = i - page * per;
                String m = list.get(i);
                addRenderableWidget(Button.builder(Component.literal(m), b -> pick(m)).bounds(left + 10 + (k % cols) * (cw + 4), top + 32 + (k / cols) * 20, cw, 18).build());
            }
            if (pages > 1) {
                addRenderableWidget(Button.builder(Component.literal("◀"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(left + 10, top + h - 24, 20, 18).build());
                addRenderableWidget(Button.builder(Component.literal("▶"), b -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).bounds(left + 34, top + h - 24, 20, 18).build());
            }
            addRenderableWidget(Button.builder(Component.literal("돌아가기"), b -> onClose()).bounds(left + w - 80, top + h - 24, 70, 18).build());
        }

        private void pick(String m) { done.accept(m); Minecraft.getInstance().setScreen(parent); }

        @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

        @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
            Art.ui(g, "panels/screen-dimmer", 0, 0, width, height, 0xCCFFFFFF);
            int w = Math.min(420, width - 16), h = Math.min(260, height - 16), left = (width - w) / 2, top = (height - h) / 2;
            Art.ui(g, "panels/panel-fill", left, top, w, h, 0xFFFFFFFF);
            Art.nine(g, "panels/modal-panel", left - 4, top - 4, w + 8, h + 8, 28, 9, 0xFFFFFFFF);
        }
    }
}
