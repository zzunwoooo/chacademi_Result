package kr.chacademy.story.dialogue;

import java.util.List;
import java.util.Map;

/**
 * dialogue.yml 한 개. 형식은 FORMAT.md 의 "대화" 부분 참고.
 * 화면 모양(어둡게·타자기 속도·글자 소리·글꼴)은 MagicCodex 대화창이 정하므로 여기에는 내용과 흐름만 있다.
 */
public record Dialogue(
        String id,
        String title,
        String start,
        Map<String, Speaker> speakers,
        Map<String, Scene> scenes,
        DialogueCommands commands,
        String description, String chapter) {
    public Dialogue(String id,String title,String start,Map<String,Speaker> speakers,Map<String,Scene> scenes,DialogueCommands commands,String description){this(id,title,start,speakers,scenes,commands,description,"");}
    public Dialogue(String id,String title,String start,Map<String,Speaker> speakers,Map<String,Scene> scenes,DialogueCommands commands){
        this(id,title,start,speakers,scenes,commands,"");
    }
    public Dialogue(String id,String title,String start,Map<String,Speaker> speakers,Map<String,Scene> scenes){
        this(id,title,start,speakers,scenes,DialogueCommands.EMPTY);
    }


    /** 화자. portraits 는 표정 이름 → 그림 파일. 첫 번째가 기본 표정. */
    public record Speaker(String id, String name, String npc, Map<String, String> portraits) {
        public String defaultFace() {
            return portraits.isEmpty() ? null : portraits.keySet().iterator().next();
        }

        /** 플레이어 자신 (편집기의 "나"). ChacaPortrait 로 만든 내 일러스트가 있으면 그걸 보여 준다. */
        public boolean isPlayer() {
            return "me".equals(id) || "{player}".equals(name.strip());
        }
    }

    /** 대사 한 줄 = 클릭 한 번. face 를 비우면 그 화자의 앞 표정을 유지. */
    public record Line(String who, String face, String text) {
    }

    /** 호감도 조건. npc 의 호감도가 min 이상 max 이하이면 참 (max 가 null 이면 상한 없음). */
    public record Need(String npc, int min, Integer max) {
        public boolean test(Map<String, Integer> affinity) {
            int v = affinity.getOrDefault(npc, Affinity.DEFAULT);
            return v >= min && (max == null || v <= max);
        }
    }

    /** 호감도 변화 한 개 (npc 에게 add 만큼, 음수면 내려감). */
    public record AffinityChange(String npc, int add) {
    }

    /** 선택지. need 가 있으면 조건을 만족할 때만 보임. affinity 는 고르면 바뀌는 호감도 (여러 명). */
    public record Choice(String text, String to, Need need, List<AffinityChange> affinity, String event) {
    }

    /** 장면에 들어올 때 조건이 맞으면 다른 장면으로 보냄. */
    public record Redirect(Need need, String to) {
    }

    /** affinity = 이 장면에 들어오면 바뀌는 호감도 (대화마다 한 번). */
    public record Scene(String id, List<Redirect> redirects, List<Line> lines, List<Choice> choices,
                        String next, String event, List<AffinityChange> affinity) {
    }

    /** 이 대화에서 쓰이는 모든 그림 파일 이름. */
    public List<String> imageNames() {
        return speakers.values().stream().flatMap(s -> s.portraits().values().stream()).distinct().toList();
    }
}
