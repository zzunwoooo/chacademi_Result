package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/** 마우스로 누르는 창 (가챠 기계, 메뉴, 유저 선택, 닉네임) */
public interface Panel {
    String screen();
    void render(Canvas c, long now, float mx, float my);
    /** 왼쪽 클릭. 처리했으면 true */
    boolean click(float mx, float my, long now);
    default boolean scroll(float mx, float my, double amount) { return false; }
    /** 왼쪽 버튼을 누른 채 움직임 (캐릭터 돌리기 등) */
    default void drag(float mx, float my) {}
    /** 왼쪽 버튼 뗌 */
    default void release(float mx, float my) {}
    /** GLFW 키 코드 */
    default boolean key(int key, int mods, long now) { return false; }
    default boolean typed(char ch, long now) { return false; }
    void update(JsonObject data, long now);
    /** 이 창이 처리하는 연출이면 true */
    default boolean fx(String id, JsonObject data, long now) { return false; }
    /** ESC 로 닫을 수 있는지 */
    default boolean closable() { return true; }
    /** 클릭 직전 보조키 (1 = Shift, 2 = Ctrl) */
    default void modifiers(int mods) {}
    default void closed() {}
    /** 글자 입력칸이 있는 창 (열려 있는 동안 윈도우 입력기를 끔) */
    default boolean textInput() { return false; }
}
