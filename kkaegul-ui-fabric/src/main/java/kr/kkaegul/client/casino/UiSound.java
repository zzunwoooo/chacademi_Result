package kr.kkaegul.client.casino;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** 엘시 UI 효과음 (버튼, 구매, 빚 갚기 등) */
public final class UiSound {

    /** 섯다 화면의 소리 버튼과 같이 쓰임 */
    public static boolean enabled = true;

    // 클릭 판정: 화면이 클릭을 처리하는 동안 버튼에 닿았는지
    private static boolean clicking, hit;

    private UiSound() {
    }

    public static void play(SoundEvent ev, float pitch, float volume) {
        if (!enabled || ev == null) return;
        try {
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(ev, pitch, volume));
        } catch (Throwable ignored) {
        }
    }

    /** 버튼 누름 */
    public static void click() {
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f);
    }

    /** 성공 (구매, 저금, 받기 등) */
    public static void success() {
        play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.15f, 0.55f);
    }

    /** 큰 성공 (빚 완납, 승리) */
    public static void fanfare() {
        play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f, 0.6f);
    }

    /** 실패 · 거절 */
    public static void error() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7f, 0.6f);
    }

    /** 돈이 들어옴 */
    public static void coins() {
        play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f, 0.5f);
        play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 0.6f);
    }

    /** 칩을 걸 때 */
    public static void chips() {
        play(SoundEvents.BLOCK_CHAIN_PLACE, 1.6f, 0.45f);
    }

    /** 아이템 받기 */
    public static void pickup() {
        play(SoundEvents.ENTITY_ITEM_PICKUP, 1.0f, 0.6f);
    }

    /** 창 열기 */
    public static void open() {
        play(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.15f, 0.55f);
    }

    // ----- 클릭 판정 -----

    public static void beginClick() {
        clicking = true;
        hit = false;
    }

    /** 화면의 영역 판정(in)에서 호출. 클릭 중 버튼에 닿았으면 기록 */
    public static boolean mark(boolean inside) {
        if (clicking && inside) hit = true;
        return inside;
    }

    public static void endClick() {
        if (clicking && hit) click();
        clicking = false;
        hit = false;
    }
}
