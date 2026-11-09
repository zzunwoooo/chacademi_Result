package school.chacademia.tower;

import java.util.List;
import org.bukkit.entity.Player;

/** 마법 쪽 연결 (MagicCodexBridge). 탑에 들어갈 때 마법·지능을 맡기고, 층마다 배우고, 나갈 때 되돌린다. */
interface Magic {
    record SpellInfo(String id, String name, int circle) {}

    boolean ready();

    /** 전투 마법 (탑 카드 후보). */
    List<SpellInfo> combatSpells(Settings s);

    /** 시작 카드 후보 (기본 공격 마법). */
    List<SpellInfo> basicSpells(Settings s);

    /** 지금 마법 상태를 글자로 (맡기기 파일에 들어감). */
    String snapshot(Player p);

    /** 탑 시작 상태로: 배운 마법 모두 빼고 지능 보너스 0. */
    void enterTower(Player p);

    boolean learn(Player p, String spell);

    void addIntelligence(Player p, int amount);

    /** 맡긴 상태로 되돌리기 (탑에서 배운 마법·지능은 사라짐). state 가 null/빈 값이면 탑 것만 지운다. */
    void restore(Player p, String state);

    /** 브리지가 계산한 지금 지능 (탑 안 피해를 탑 지능 기준으로 다시 맞출 때). */
    double realPower(Player p);

    /** 꺼내 놓은 펫(MCPets 바닐라 펫·동행 펫)을 들여보낸다. 탑 안에서는 매 tick 불러 다시 꺼내도 바로 들어간다. */
    void dismissPets(Player p);
}
