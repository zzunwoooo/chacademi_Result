package school.magiccodex.client;

import java.util.List;

/** Display model; built-in demo data below is used only by development checks. */
public final class CodexData {
    private CodexData() {}

    public enum Category {
        ALL("전체 마법"), WIND("바람"), FIRE("화염"), WATER("물"), EARTH("대지"), LIGHT("빛"), DARK("어둠");
        public final String label;
        Category(String label) { this.label = label; }
    }

    public record Spell(String id, String name, Category category, String description,
                        String condition, String research, double cooldown, boolean discovered,
                        String icon, String permission, String command, int order, boolean permissionKnown, int manaCost) {
        public Spell { icon = GimmickSpellIcons.resolve(id, MobSpellIcons.resolve(id, icon)); }
        public Spell(String id, String name, Category category, String description,
                     String condition, String research, double cooldown, boolean discovered,
                     String icon, String permission, String command, int order, boolean permissionKnown) {
            this(id,name,category,description,condition,research,cooldown,discovered,
                    icon,permission,command,order,permissionKnown,0);
        }
        public Spell(String id, String name, Category category, String description,
                     String condition, String research, double cooldown, boolean discovered) {
            this(id,name,category,description,condition,research,cooldown,discovered,
                    "magiccodex:textures/spells/"+id+".png","magic.learned."+id,"cast "+id,0,true);
        }
        public Spell withPermission(Boolean granted) {
            return new Spell(id,name,category,description,condition,research,cooldown,
                    Boolean.TRUE.equals(granted),icon,permission,command,order,granted!=null,manaCost);
        }
    }

    public static List<Spell> previewSpells() {
        return List.of(
            spell("magical_flame", "작은 불씨", Category.FIRE, "손끝에서 작은 불씨를 피워냅니다. 어두운 교실과 차가운 난로를 밝힐 수 있습니다.", "불이 붙은 상태로 30초 버티기", "화염 수업에서 발견한 첫 번째 마법.", 10, true),
            spell("feather_step", "깃털 걸음", Category.WIND, "몸을 깃털처럼 가볍게 만들어 잠시 공중에 머무릅니다.", "공중에 10초 동안 머무르기", "높은 탑에서 바람의 흐름을 관찰해 보세요.", 15, true),
            spell("water_drop", "샘의 물방울", Category.WATER, "맑은 물방울을 모아 작은 샘을 만들어 냅니다.", "학교 분수에서 물의 기록 읽기", "오래된 분수에는 작은 비밀이 있습니다.", 8, true),
            spell("stone_skin", "돌의 숨결", Category.EARTH, "대지의 기운을 빌려 몸을 단단하게 보호합니다.", "광물 표본 3종을 조사하기", "지하 광물실에 남아 있는 연구 기록.", 20, false),
            spell("starlight", "별빛 등불", Category.LIGHT, "손바닥 위에 별빛을 띄워 주변을 은은하게 밝힙니다.", "천문탑에서 별자리 관찰하기", "별이 보이는 밤에 탑을 찾아가 보세요.", 12, true),
            spell("shadow_veil", "그림자 장막", Category.DARK, "그림자를 얇은 장막처럼 펼쳐 자신의 흔적을 감춥니다.", "밤에 금서관의 어둠 기록 읽기", "그림자는 빛이 사라진 자리에 머뭅니다.", 25, false),
            spell("ember_ring", "잿불 고리", Category.FIRE, "작은 잿불들이 둥근 고리를 이루며 주위를 맴돕니다.", "불씨를 배운 뒤 화염 수업 듣기", "불씨를 다루는 데 익숙해진 학생의 기록.", 18, false),
            spell("wind_bell", "바람의 종", Category.WIND, "바람을 울려 멀리 있는 친구에게 작은 신호를 보냅니다.", "풍향계 옆에서 바람 소리 듣기", "종탑에 머무는 바람을 찾아보세요.", 10, false),
            spell("mist", "새벽 안개", Category.WATER, "차가운 수분을 모아 발밑에 옅은 안개를 만듭니다.", "이른 아침 호숫가 탐사하기", "호수와 숲이 만나는 곳의 관찰 기록.", 22, true),
            spell("root_bond", "뿌리의 약속", Category.EARTH, "대지 속 뿌리를 불러 주변에 작은 생명의 흔적을 남깁니다.", "온실에서 오래된 씨앗 기르기", "온실 관리인이 남긴 씨앗 연구 기록.", 16, true),
            spell("moon_beam", "달빛 실", Category.LIGHT, "가느다란 달빛을 실처럼 엮어 밤길을 안내합니다.", "달빛 아래에서 천문학 수업 듣기", "달의 모양에 따라 빛의 결이 바뀝니다.", 14, false),
            spell("night_echo", "밤의 메아리", Category.DARK, "고요한 어둠에 귀를 기울여 멀리 남은 울림을 듣습니다.", "어두운 복도에서 발소리 관찰하기", "밤의 학교에서만 들을 수 있는 소리.", 18, true),
            spell("hearth", "따스한 난로", Category.FIRE, "은은한 온기를 만들어 추운 날의 휴식을 돕습니다.", "기숙사의 꺼진 난로 조사하기", "불길보다 온기를 아끼는 마법입니다.", 30, true),
            spell("updraft", "상승 기류", Category.WIND, "발아래 바람을 모아 짧은 상승 기류를 만듭니다.", "탑 꼭대기에서 기류 조사하기", "날아오르기 전에 바람을 읽어야 합니다.", 24, false),
            spell("ripple", "물결 편지", Category.WATER, "잔잔한 물 위에 작은 물결로 인사를 남깁니다.", "호수의 물결을 세 번 관찰하기", "말 없이 마음을 전하는 오래된 방법.", 9, false),
            spell("crystal", "수정의 기억", Category.EARTH, "작은 수정에 주변의 기운을 잠시 담아 둡니다.", "동굴에서 수정 표본 조사하기", "수정마다 서로 다른 빛이 남습니다.", 28, false),
            spell("dawn", "새벽의 인사", Category.LIGHT, "희미한 빛을 모아 새로운 하루를 밝힙니다.", "학교 정문에서 일출 맞이하기", "첫 햇살을 기다린 학생이 남긴 기록.", 20, true),
            spell("ink_whisper", "먹빛 속삭임", Category.DARK, "먹빛 기운을 모아 오래된 책장에 남은 흔적을 읽습니다.", "금서관의 지워진 문장 조사하기", "어둠은 잊힌 이야기를 품고 있습니다.", 30, false)
        );
    }

    private static Spell spell(String id, String name, Category category, String description, String condition,
                               String research, double cooldown, boolean discovered) {
        return new Spell(id, name, category, description, condition, research, cooldown, discovered);
    }
}
