package kr.chacademy.storyplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** 다른 플러그인(퀘스트 등)이 들을 수 있는 스토리 이벤트들. */
public final class StoryEvents {
    private StoryEvents() {}

    /** 컷신을 다 봤거나 건너뜀. missingMod = 모드가 없어서 재생을 못 함. */
    public static class CutsceneFinish extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final String id;
        private final boolean skipped, missingMod;

        public CutsceneFinish(Player player, String id, boolean skipped, boolean missingMod) {
            this.player = player;
            this.id = id;
            this.skipped = skipped;
            this.missingMod = missingMod;
        }

        public Player getPlayer() { return player; }
        public String getCutsceneId() { return id; }
        public boolean isSkipped() { return skipped; }
        public boolean isMissingMod() { return missingMod; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** 대화에서 이벤트가 있는 선택지를 고름 / 이벤트가 있는 장면에 들어감. */
    public static class DialogueChoice extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final String id, event;

        public DialogueChoice(Player player, String id, String event) {
            this.player = player;
            this.id = id;
            this.event = event;
        }

        public Player getPlayer() { return player; }
        public String getDialogueId() { return id; }
        public String getEventName() { return event; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** 대화가 끝남. lastScene = 마지막 장면 id (결말 구분용). */
    public static class DialogueFinish extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final String id, lastScene;
        private final boolean missingMod;

        public DialogueFinish(Player player, String id, String lastScene, boolean missingMod) {
            this.player = player;
            this.id = id;
            this.lastScene = lastScene;
            this.missingMod = missingMod;
        }

        public Player getPlayer() { return player; }
        public String getDialogueId() { return id; }
        public String getLastScene() { return lastScene; }
        public boolean isMissingMod() { return missingMod; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
