package kr.chacademy.story.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 스토리 퀘스트 HUD (레퍼런스: 금색 장식선 + 접기 −/+ 버튼 + 책 아이콘 · 장 이름 / 큰 글씨 설명 / 작은 글씨 조건 진행도 / 목표 완료 체크).
 * 오른쪽 중간 (MagicCodex 마법 슬롯·몹 체력바·디버프와 겹치지 않는 곳). 접기는 채팅창을 연 상태에서 −/+ 를 클릭.
 */
final class QuestHud {
    private QuestHud() {}

    private static final float WIDTH = 158, TOGGLE = 13, LINE_H = 14;
    private static final float CHAPTER_SIZE = 7.2f, DESC_SIZE = 10.5f, COND_SIZE = 7.4f;
    private static final int WHITE = 0xFFFFFFFF, CHAPTER = 0xFFF4EBD2, COND = 0xFFEDEDED, COND_DONE = 0xFFB7B7B7, GOLD = 0xFFFFE08A;
    /** 토글 버튼 클릭 판정 (GUI 좌표). 그릴 때마다 갱신. */
    private static float toggleX = -100, toggleY = -100;

    static boolean hitToggle(double mx, double my) {
        if(HudEditing.editing())return false;var point=HudEditing.pointer(mx,my);mx=point[0];my=point[1];
        return QuestClient.view.active() && mx >= toggleX - 2 && mx <= toggleX + TOGGLE + 2 && my >= toggleY - 2 && my <= toggleY + TOGGLE + 2;
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        var mc = Minecraft.getInstance();
        var v = QuestClient.view;
        if (!v.active() || mc.player == null || mc.options.hideGui) { toggleX = -100; return; }
        if (mc.screen != null && !QuestClient.hudScreen(mc.screen)) { toggleX = -100; return; }
        long now = QuestClient.now();
        float fade = Mth.clamp((now - v.shownAt) / 350f, 0, 1);
        int a = Math.round(255 * fade);
        if (a <= 2) return;

        float sw = g.guiWidth(), sh = g.guiHeight();
        float right = sw - 8, x0 = right - WIDTH;
        float estimatedHeight=TOGGLE+12;if(!QuestClient.collapsed){estimatedHeight+=wrap(g,v.description,DESC_SIZE,WIDTH-TOGGLE-5).size()*DESC_SIZE*1.32f+5;for(var condition:v.conds)estimatedHeight+=(wrap(g,condition.label()+" "+condition.current()+"/"+condition.required(),COND_SIZE,WIDTH-TOGGLE-5).size()+.3f)*COND_SIZE*1.55f;}
        // 기본 자리(오른쪽 30%)가 MagicCodex 파티 HUD(오른쪽 가운데)와 겹치면 파티 위로, 자리가 없으면 아래로 비킨다 (사용자가 옮긴 위치는 그대로)
        float y0 = QuestHudPlacement.defaultTop(Math.round(sh * 0.30f), x0, right, estimatedHeight, HudEditing.box("party"));
        try(var customization=HudEditing.begin(g,x0,y0-9,WIDTH,estimatedHeight)){
        toggleX = x0; toggleY = y0;

        // 장식선: 왼쪽 꺾임(버튼에서 시작) · 가운데 늘림 · 오른쪽 마름모
        QuestArt.image("line").ifPresent(t -> {
            float lx0 = x0 + TOGGLE * 0.72f, ly0 = y0 - 9, ly1 = ly0 + LINE_H;
            float k = LINE_H / t.height();
            float leftW = 60 * k, rightW = 64 * k;
            int c = argb(a, 0xFFFFFF);
            float u1 = 60f / t.width(), u2 = (t.width() - 64f) / t.width();
            QuestArt.quad(g, t, lx0, ly0, lx0 + leftW, ly1, 0, 0, u1, 1, c, c, c, c);
            QuestArt.quad(g, t, lx0 + leftW, ly0, right - rightW, ly1, u1, 0, u2, 1, c, c, c, c);
            QuestArt.quad(g, t, right - rightW, ly0, right, ly1, u2, 0, 1, 1, c, c, c, c);
        });
        QuestArt.image(g, QuestClient.collapsed ? "toggle_plus" : "toggle_minus", x0, y0, TOGGLE, TOGGLE, argb(a, 0xFFFFFF));

        float cx = x0 + TOGGLE + 5;
        float row = y0 + TOGGLE / 2f + 1;
        float book = 11;
        QuestArt.image(g, "book", cx, row - book / 2f, book, book * 0.94f, argb(a, 0xFFFFFF));
        boolean allDone = v.ready && !v.conds.isEmpty();
        if (allDone) {
            // 목표 완료 순간: 책 옆 별이 반짝 (처음 2.5초 강하게, 그 뒤 은은하게)
            float since = (now - v.readyAt) / 1000f;
            float pulse = since < 2.5f ? 0.55f + 0.45f * Mth.sin(since * 9f) : 0.35f + 0.25f * Mth.sin(since * 2.2f);
            float gs = 8 * (0.85f + 0.3f * pulse);
            QuestArt.image(g, "glint", cx - gs * 0.45f, row - book / 2f - gs * 0.55f, gs, gs, argb(Math.round(a * Mth.clamp(pulse, 0, 1)), 0xFFFFFF));
        }
        QuestArt.textLeft(g, v.chapter, cx + book + 4, row, CHAPTER_SIZE, argb(a, CHAPTER));
        if (QuestClient.collapsed) return;

        float textX = cx;
        float maxW = right - textX;
        float y = row + book / 2f + 5;
        if (!v.description.isBlank()) {
            for (String line : wrap(g, v.description, DESC_SIZE, maxW)) {
                float lh = DESC_SIZE * 1.32f;
                QuestArt.textLeft(g, line, textX, y + lh / 2f, DESC_SIZE, argb(a, WHITE));
                y += lh;
            }
            y += 2;
        }
        float ch = COND_SIZE * 1.55f;
        if (allDone) {
            float icon = 9;
            QuestArt.image(g, "check", textX, y + ch / 2f - icon / 2f, icon, icon, argb(a, 0xFFFFFF));
            QuestArt.textLeft(g, "목표 완료", textX + icon + 3, y + ch / 2f, COND_SIZE + 0.4f, argb(a, GOLD));
            return;
        }
        for (var c : v.conds) {
            boolean done = c.current() >= c.required();
            String text = c.label() + " " + Math.min(c.current(), c.required()) + "/" + c.required();
            float tx = textX;
            if (done) {
                float icon = 8;
                QuestArt.image(g, "check", textX, y + ch / 2f - icon / 2f, icon, icon, argb(a, 0xFFFFFF));
                tx += icon + 3;
            }
            // 긴 조건은 HUD 폭 안에서 줄바꿈 (예전: 한 줄로 그려 화면 오른쪽 밖으로 잘림, 높이 추정은 이미 줄바꿈 기준)
            var lines = wrap(g, text, COND_SIZE, right - tx);
            for (String line : lines) {
                QuestArt.textLeft(g, line, tx, y + ch / 2f, COND_SIZE, argb(a, done ? COND_DONE : COND));
                y += ch;
            }
        }

        }
}

    private static int argb(int a, int rgb) { return (Mth.clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    /** 공백 기준으로 줄을 나누고, 한 단어가 너무 길면 글자 단위로 자른다. 최대 3줄. */
    private static List<String> wrap(GuiGraphics g, String text, float size, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (QuestArt.width(g, trial, size) <= maxW) { line.setLength(0); line.append(trial); continue; }
            if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
            if (QuestArt.width(g, word, size) <= maxW) { line.append(word); continue; }
            for (int i = 0; i < word.length(); i++) {
                String t = line.toString() + word.charAt(i);
                if (QuestArt.width(g, t, size) > maxW && !line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                line.append(word.charAt(i));
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > 3) {
            List<String> cut = new ArrayList<>(out.subList(0, 3));
            cut.set(2, cut.get(2) + "…");
            return cut;
        }
        return out;
    }
}
