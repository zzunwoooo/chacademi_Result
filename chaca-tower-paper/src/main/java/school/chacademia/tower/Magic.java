package school.chacademia.tower;

import java.util.List;
import org.bukkit.entity.Player;

/** 마법 쪽 연결 (MagicCodexBridge). 탑에 들어갈 때 마법·지능을 맡기고, 층마다 배우고, 나갈 때 되돌린다. */
interface Magic {
    record SpellInfo(String id, String name, int circle, int cost) {}

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
    /** @return 장신구·아티팩트까지 제대로 돌려놨으면 true (브리지가 꺼져 있으면 false — 그때는 나중에 다시 해야 한다). */
    boolean restore(Player p, String state);

    /** 접속 때: 지난번 서버가 갑자기 꺼져 남았을 수 있는 탑 마나 수정치를 지운다. */
    void clearMana(Player p);

    /** 브리지가 계산한 지금 지능 (탑 안 피해를 탑 지능 기준으로 다시 맞출 때). */
    double realPower(Player p);

    /** 꺼내 놓은 펫(MCPets 바닐라 펫·동행 펫)을 들여보낸다. 탑 안에서는 매 tick 불러 다시 꺼내도 바로 들어간다. */
    void dismissPets(Player p);

    /** 탑 안 마나 최대치·초당 회복을 이 값으로 고정 (restore 가 푼다). */
    void pinMana(Player p, double max, double regen);

    /** 지금 마나 / 최대 마나 (0~1). */
    double manaFraction(Player p);

    void addMana(Player p, double amount);

    /** 마나를 쓴다 (모자라면 false, 안 씀). */
    boolean consumeMana(Player p, double amount);

    /** 들어갈 때 기억한 습득 목록을 버린다 (두 번째 되돌리기 뒤). */
    void forgetLearned(Player p);

    /** 판 중: 마법 발견이 멈춰 있게 (들어온 직후 불러오기가 끝나 다시 켜진 경우). */
    void holdDiscovery(Player p);

    /** 시전 가속 (재사용 대기 감소 % = 100·h/(100+h)). 0 이면 지운다. restore 가 푼다. */
    void setHaste(Player p, double haste);

    /** 이 마법들 중 재사용 대기가 가장 많이 남은 하나를 바로 끝낸다. @return 그 마법 이름 (대기 중인 게 없으면 null) */
    String reload(Player p, List<String> spells);

    /** 들어갈 때 진짜로 배운 마법(LuckPerms 권한) 목록을 기억해 두고, 나갈 때 그 사이 새로 붙은 것을 뗀다 (탑 안에서는 영구 습득 없음). */
    void rememberLearned(Player p);
    void unlearnNew(Player p);
}
