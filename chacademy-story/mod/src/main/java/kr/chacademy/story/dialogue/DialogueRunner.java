package kr.chacademy.story.dialogue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 메인 스토리 대화의 진행기. <b>화면을 그리지 않는다.</b>
 * 대사·선택지·갈림길·호감도 조건 같은 내용과 흐름만 갖고, 지금 보여 줄 것을 "프레임"(Map)으로 내놓는다.
 * 프레임은 MagicCodex 대화창(school.magiccodex.client.api.ExternalDialogue)이 그대로 그린다 → 게임 안 대화창은 한 가지뿐이다.
 * <ul>
 *   <li>대사 한 줄(또는 선택지만 있는 장면) = 프레임 하나. 화면이 바뀔 때마다 sequence 가 커진다</li>
 *   <li>"나"가 말하는 줄은 playerPortrait (ChacaPortrait 내 일러스트), 그 밖에는 화자의 표정 그림 파일</li>
 *   <li>{player} = MagicCodex 한글 닉네임, {account} = 마인크래프트 닉네임, %...% = 서버가 보낸 플레이스홀더 값</li>
 *   <li>장면의 마지막 대사에 선택지가 붙는다 (호감도 조건에 맞는 것만)</li>
 *   <li>장면에 들어올 때 호감도에 따라 다른 장면으로 보내기 (redirect)</li>
 * </ul>
 * Minecraft 타입을 쓰지 않는다 (단위 테스트 가능). 모든 메서드는 클라이언트 스레드에서 부른다.
 */
public final class DialogueRunner {
    public interface Listener {
        /** 선택지를 골랐거나 이벤트가 있는 장면에 들어감. 서버가 연 대화면 서버로 보낸다. */
        void event(String dialogueId, String event, String npc, int add);

        default boolean affinity(String dialogueId,String key,List<Dialogue.AffinityChange> changes) { return true; }

        /** 대화가 끝남. lastScene = 마지막 장면 id. */
        void done(String dialogueId, String lastScene);
        default void aborted(String dialogueId) {}

        /** 지금 보고 있는 곳 (장면, 대사 번호). 서버가 저장해 두었다가 다시 접속하면 여기서부터 연다. */
        default void progress(String dialogueId, String scene, int line) {
        }
    }

    /** 선택지 id 머리말. 프레임의 선택지 id 는 c0, c1, ... (지금 보이는 선택지 순서). */
    private static final String CHOICE = "c";
    /** 한 프레임에 미리 읽어 두라고 알리는 그림 수 (MagicCodex 가 받는 한도 6 보다 작게: 지금 그림 + 5). */
    private static final int MAX_PRELOAD = 5;

    private final Dialogue dialogue;
    private final Path folder;
    private final Map<String, Integer> affinity;
    private final Listener listener;
    private final Map<String, String> vars;
    private final String session;

    private Dialogue.Scene scene;
    private int lineIndex = 0;
    private int sequence = 0;
    private boolean ended = false;

    private final Map<String, String> faces = new HashMap<>();
    private String lastPortrait = null;

    /**
     * 초상화 리소스(리소스팩 chacademia_npc → magiccodex:textures/gui/dialogue/&lt;이름&gt;.png)에 그 이름이 있는지.
     * 클라이언트가 켜질 때 정해 준다. 기본값(테스트)은 없음 → 예전처럼 대화 폴더의 그림 파일.
     */
    public static volatile java.util.function.Predicate<String> packPortrait = name -> false;
    private static final java.util.regex.Pattern PACK_NAME = java.util.regex.Pattern.compile("[a-z0-9_/-]{1,120}");

    /** 대화 파일의 그림 이름(예: bram-neutral.png) → 리소스팩 초상화 이름(bram-neutral). 리소스팩에 없으면 null. */
    static String packName(String file) {
        if (file == null) return null;
        String n = file.strip().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) n = n.substring(0, n.length() - 4);
        if (!PACK_NAME.matcher(n).matches()) return null;
        try { return packPortrait.test(n) ? n : null; } catch (RuntimeException e) { return null; }
    }
    private List<Dialogue.Choice> visibleChoices = List.of();
    private final Set<String> enteredAffinity = new HashSet<>();

    public DialogueRunner(Dialogue dialogue, Path folder, Map<String, Integer> affinity,
                          Map<String, String> vars, Listener listener) {
        this(dialogue, folder, affinity, vars, listener, "", 0);
    }

    /**
     * @param folder 표정 그림이 있는 폴더 (config/chaca_dialogue/&lt;id&gt;). null 이면 그림 없이.
     * @param startScene startLine 이어서 보기 (비우면 처음부터).
     */
    public DialogueRunner(Dialogue dialogue, Path folder, Map<String, Integer> affinity,
                          Map<String, String> vars, Listener listener, String startScene, int startLine) {
        this.dialogue = dialogue;
        this.folder = folder;
        this.affinity = new HashMap<>(affinity);
        this.listener = listener;
        this.vars = vars;
        this.session = "story:" + dialogue.id() + ":" + java.util.UUID.randomUUID();
        boolean resume = startScene != null && dialogue.scenes().containsKey(startScene);
        if (resume) {
            // 이어서 보기: 저장된 곳으로 바로 간다. 장면 들어옴 효과(redirect·호감도·이벤트)는 다시 실행하지 않는다
            scene = dialogue.scenes().get(startScene);
            enteredAffinity.add(startScene);
            lineIndex = Math.max(0, Math.min(startLine, Math.max(0, scene.lines().size() - 1)));
            startLine();
            report();
        } else enter(dialogue.start(), 0);
    }

    private void report() {
        if (!ended && scene != null) listener.progress(dialogue.id(), scene.id(), lineIndex);
    }

    // ---------------------------------------------------------------- 진행

    private void enter(String sceneId, int hops) {
        Dialogue.Scene s = dialogue.scenes().get(sceneId);
        if (s == null || hops > 20) {
            finish(scene == null ? sceneId : scene.id());
            return;
        }
        for (Dialogue.Redirect r : s.redirects()) {
            if (r.need().test(affinity) && dialogue.scenes().containsKey(r.to()) && !r.to().equals(sceneId)) {
                enter(r.to(), hops + 1);
                return;
            }
        }
        scene = s;
        if (!s.affinity().isEmpty() && enteredAffinity.add(s.id()) && listener.affinity(dialogue.id(), "scene."+s.id(),s.affinity())) {
            for (Dialogue.AffinityChange a : s.affinity()) affinity.merge(a.npc(), a.add(), Integer::sum);
        }
        if (!s.event().isEmpty()) listener.event(dialogue.id(), s.event(), "", 0);
        lineIndex = 0;
        startLine();
        report();
        if (s.lines().isEmpty() && visibleChoices.isEmpty()) goNext(hops);
    }

    /** 새 프레임. 표정을 정하고, 장면의 마지막 대사면 보일 선택지를 정한다. */
    private void startLine() {
        sequence++;
        if (lineIndex < scene.lines().size()) {
            String portrait = portraitFor(scene.lines().get(lineIndex), faces);
            if (portrait != null) lastPortrait = portrait;
        }
        visibleChoices = atLastLine() ? visibleChoices() : List.of();
    }

    /** 이 대사에서 화자가 쓸 표정 그림. 표정을 안 적었으면 그 화자의 앞 표정. 그림이 없는 화자면 null (앞 그림을 그대로 둔다). */
    private String portraitFor(Dialogue.Line line, Map<String, String> faces) {
        Dialogue.Speaker sp = dialogue.speakers().get(line.who());
        if (sp == null || sp.portraits().isEmpty()) return null;
        String face = !line.face().isEmpty() && sp.portraits().containsKey(line.face()) ? line.face()
                : faces.getOrDefault(sp.id(), sp.defaultFace());
        faces.put(sp.id(), face);
        return sp.portraits().get(face);
    }

    private List<Dialogue.Choice> visibleChoices() {
        List<Dialogue.Choice> out = new ArrayList<>();
        for (Dialogue.Choice c : scene.choices()) if (c.need() == null || c.need().test(affinity)) out.add(c);
        return out;
    }

    private boolean atLastLine() {
        return lineIndex >= scene.lines().size() - 1;
    }

    /**
     * 대화창에서 온 입력. "" = 선택지 없는 프레임을 넘김 (다음 대사), "c번호" = 그 선택지를 고름.
     * 이 뒤에 {@link #isEnded()} 를 보고, 안 끝났으면 {@link #frame()} 을 다시 보여 준다.
     */
    public void choose(String choice) {
        if (ended) return;
        if (choice == null || choice.isEmpty()) {
            advance();
            return;
        }
        if (!choice.startsWith(CHOICE)) return;
        try {
            pick(Integer.parseInt(choice.substring(CHOICE.length())));
        } catch (NumberFormatException ignored) {
        }
    }

    private void advance() {
        if (!atLastLine()) {
            lineIndex++;
            startLine();
            report();
            return;
        }
        if (!visibleChoices.isEmpty()) return; // 선택지를 골라야 함
        goNext(0);
    }

    private void goNext(int hops) {
        String next = scene.next();
        if (next.isEmpty() || !dialogue.scenes().containsKey(next)) finish(scene.id());
        else enter(next, hops + 1);
    }

    private void pick(int index) {
        if (index < 0 || index >= visibleChoices.size()) return;
        Dialogue.Choice c = visibleChoices.get(index);
        if (!c.affinity().isEmpty() && listener.affinity(dialogue.id(), "choice."+scene.id()+"."+scene.choices().indexOf(c),c.affinity()))
            for (Dialogue.AffinityChange a : c.affinity()) affinity.merge(a.npc(), a.add(), Integer::sum);
        if (!c.event().isEmpty()) {
            // 실제 호감도 값은 서버 affinity.events만 사용한다. npc/add는 구형 패킷 형식 호환용이다.
            Dialogue.AffinityChange first = c.affinity().isEmpty() ? null : c.affinity().get(0);
            listener.event(dialogue.id(), c.event(), first == null ? "" : first.npc(), first == null ? 0 : first.add());
        } else if (!c.affinity().isEmpty()) {
            Dialogue.AffinityChange first = c.affinity().get(0);
            listener.event(dialogue.id(), "", first.npc(), first.add());
        }
        if (c.to().isEmpty() || !dialogue.scenes().containsKey(c.to())) finish(scene.id());
        else enter(c.to(), 0);
    }

    private void finish(String lastScene) {
        if (ended) return;
        ended = true;
        listener.done(dialogue.id(), lastScene == null ? "" : lastScene);
    }

    public String dialogueId() {
        return dialogue.id();
    }

    /** MagicCodex 대화창에 넘기는 세션 이름. */
    public String session() {
        return session;
    }

    /** 끝났거나 멈췄으면 true. 생성하자마자 끝났으면 (장면이 비어 있는 등) 창을 열 필요가 없다. */
    public boolean isEnded() {
        return ended;
    }

    /** 서버가 멈추라고 할 때·접속이 끊길 때. 끝난 것으로 치지 않음 (done 을 알리지 않는다). */
    public void stop() {
        if(ended)return;ended = true;listener.aborted(dialogue.id());
    }

    // ---------------------------------------------------------------- 프레임

    private String fill(String s) {
        return TextVars.fill(s, vars);
    }

    private Dialogue.Line line() {
        return scene != null && lineIndex < scene.lines().size() ? scene.lines().get(lineIndex) : null;
    }

    /**
     * 지금 보여 줄 화면. 키는 MagicCodex ExternalDialogue 의 프레임 형식 (JDK 타입만).
     * 진행이 바뀌기 전에는 같은 sequence 로 같은 내용이 나온다 (가려졌던 창을 다시 띄울 때 그대로 쓴다).
     */
    public Map<String, Object> frame() {
        Map<String, Object> m = new HashMap<>();
        m.put("session", session);
        m.put("sequence", sequence);
        m.put("title", fill(dialogue.title()));
        Dialogue.Line line = line();
        Dialogue.Speaker sp = line == null ? null : dialogue.speakers().get(line.who());
        m.put("speaker", sp == null ? "" : fill(sp.name()));
        m.put("text", line == null ? "" : fill(line.text()));
        // "나"가 말하면 내 일러스트. 아직 없으면 MagicCodex 가 아래 그림 파일을 대신 쓴다
        m.put("playerPortrait", sp != null && sp.isPlayer());
        // 초상화: 리소스팩(이름 하나로 스토리·AI 대화 공통)이 먼저, 없으면 예전 대화 폴더의 그림 파일
        if (lastPortrait != null) {
            String pack = packName(lastPortrait);
            if (pack != null) m.put("portrait", pack);
            else if (folder != null) m.put("portraitFile", folder.resolve(lastPortrait));
        }
        List<List<String>> choices = new ArrayList<>();
        for (int i = 0; i < visibleChoices.size(); i++) choices.add(List.of(CHOICE + i, fill(visibleChoices.get(i).text())));
        m.put("choices", choices);
        m.put("closable", false); // 메인 스토리는 끝까지 (ESC 는 게임 메뉴)
        m.put("last", scene != null && atLastLine() && visibleChoices.isEmpty()
                && (scene.next().isEmpty() || !dialogue.scenes().containsKey(scene.next())));
        m.put("preload", preload());
        return m;
    }

    /** 곧 나올 그림들: 이 장면의 남은 대사, 그리고 이어질 장면들의 첫 대사. */
    private List<Path> preload() {
        if (folder == null || scene == null) return List.of();
        Set<String> names = new LinkedHashSet<>();
        Map<String, String> ahead = new HashMap<>(faces);
        for (int i = lineIndex + 1; i < scene.lines().size(); i++) {
            String p = portraitFor(scene.lines().get(i), ahead);
            if (p != null) names.add(p);
        }
        List<String> nexts = new ArrayList<>();
        for (Dialogue.Choice c : visibleChoices) nexts.add(c.to());
        nexts.add(scene.next());
        for (String id : nexts) {
            Dialogue.Scene s = dialogue.scenes().get(id);
            if (s == null || s.lines().isEmpty()) continue;
            String p = portraitFor(s.lines().get(0), new HashMap<>(ahead));
            if (p != null) names.add(p);
        }
        names.remove(lastPortrait);
        List<Path> out = new ArrayList<>();
        for (String name : names) {
            if (out.size() >= MAX_PRELOAD) break;
            if (packName(name) != null) continue; // 리소스팩 그림은 미리 읽을 필요 없음
            out.add(folder.resolve(name));
        }
        return out;
    }
}
