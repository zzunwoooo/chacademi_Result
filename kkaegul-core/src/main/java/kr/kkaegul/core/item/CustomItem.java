package kr.kkaegul.core.item;

import org.bukkit.Material;

/**
 * 서버 전용 아이템. 이름/설명/모델은 config.yml items.<id> 에서 덮어쓸 수 있다.
 * 모델은 리소스팩 item model 키(kkaegul:cola 등)로 지정 → 콜라캔/티켓 모양.
 */
public enum CustomItem {
    COLA("cola", Material.APPLE, "&c깨굴 콜라", "&7시원한 콜라 한 캔"),
    KKAEKKAERO("kkaekkaero", Material.COOKIE, "&6깨깨로", "&711월 11일 한정 과자"),
    INVEN_SAVE_TICKET("inven_save_ticket", Material.PAPER, "&e인벤세이브권", "&7죽을 때 1장 자동 사용 → 아이템 유지"),
    COIN_POUCH("coin_pouch", Material.PAPER, "&a깨굴코인 주머니", "&7{e:right} 깨굴코인 3~8개"),
    TELEPORT_TICKET("teleport_ticket", Material.PAPER, "&b텔레포트권", "&7원하는 유저에게 이동"),
    RETURN_TICKET("return_ticket", Material.PAPER, "&f귀환권", "&7사용하면 로비로 귀환 (감옥·미니게임 중 불가)"),
    GACHA_PROTECT("gacha_protect", Material.PAPER, "&d가챠 파괴 방지권", "&7가챠에서 곡괭이가 부서질 때 1회 막아줘요 (자동 사용)"),
    GAUGE_TICKET("gauge_ticket", Material.PAPER, "&6게이지 변동권", "&7{e:right} +10 / {e:shift}+{e:right} -10"),
    GAUGE_RANDOM("gauge_random", Material.PAPER, "&6랜덤 게이지 변동권", "&7운빨 게이지 -10 ~ +10 랜덤"),
    FARM_TICKET("farm_ticket", Material.PAPER, "&2농사 금액 변동권", "&7작물 시세를 즉시 바꿔요"),
    TREASURE_TICKET("treasure_ticket", Material.PAPER, "&e보물광산 이동권", "&7보물광산으로 바로 이동"),
    INVITE_TICKET("invite_ticket", Material.PAPER, "&5초대권", "&7운영자를 호출하자"),
    LAND_TICKET("land_ticket", Material.PAPER, "&a땅 구매권", "&7부동산업자에게 받은 첫 땅 구매권"),
    FAIL_PAPER("fail_paper", Material.PAPER, "&8꽝 종이", "&75개를 모으면 고급상자로 교환"),
    NORMAL_BOX("normal_box", Material.CHEST, "&f일반상자", "&7{e:right} 열기"),
    PREMIUM_BOX("premium_box", Material.ENDER_CHEST, "&b고급상자", "&7{e:right} 열기"),
    LUCKY_BOX("lucky_box", Material.TRAPPED_CHEST, "&6행운상자", "&7열면 전체 이벤트 발생!"),
    COSMETIC_BOX("cosmetic_box", Material.PINK_SHULKER_BOX, "&d치장 상자", "&7{e:right} 열기 · 아직 없는 치장품 하나");

    public final String id;
    public final Material material;
    public final String defName;
    public final String defLore;

    CustomItem(String id, Material material, String defName, String defLore) {
        this.id = id;
        this.material = material;
        this.defName = defName;
        this.defLore = defLore;
    }

    public static CustomItem byId(String id) {
        for (CustomItem c : values()) if (c.id.equalsIgnoreCase(id)) return c;
        return null;
    }
}
