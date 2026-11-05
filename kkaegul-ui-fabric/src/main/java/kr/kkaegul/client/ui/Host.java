package kr.kkaegul.client.ui;

/** 화면 코드가 게임 쪽에 요청하는 것들 */
public interface Host {
    /** 서버로 버튼 액션 전송: send("gacha.spin", "machine", "2") */
    void send(String action, String... kv);
    void sound(String id, float pitch, float volume);
    /** 창 열기 / 닫기 */
    void openPanel(Panel panel);
    void closePanel(Panel panel);
    /** 지금 열려 있는 창 (없으면 null) */
    Panel openPanel();
}
