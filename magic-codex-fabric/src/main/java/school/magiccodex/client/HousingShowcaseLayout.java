package school.magiccodex.client;

/**
 * #108 (2026-10-26) 하우징 특수 가구 전시·구경 화면의 좌표·그림 경로를 한곳에 모은 것.
 *
 * <p><b>디자인 납품 대기 (오너 결정 2026-10-26):</b> 아래 4개 화면은 GPT 가 새 디자인을 만든다. 지금은 기존 창·카드를 그대로 쓰고,
 * 납품되면 이 클래스의 경로·좌표만 바꾸고 표시된 교체 지점({@code 교체 지점})에서 새 화면으로 바꾼다.
 * <ol>
 *   <li>빗자루 거치대 상세 카드 — 지금: 방 안 가구 이름표 + 방 알림(토스트)에 빗자루 이름. 교체 지점 {@code HousingScreen.useFurniture "broom_rack"}</li>
 *   <li>아티팩트 진열장 구경 창 — 지금: 아티팩트 도감(ArtifactCodexScreen) 구경 모드(읽기 전용). 교체 지점 {@code HousingScreen.useFurniture "artifact_cabinet"}</li>
 *   <li>큐디치 트로피장 전적 카드 — 지금: 결투장 로비 전적 탭(KyudichiLobbyScreen, 주인 닉네임 검색). 교체 지점 {@code HousingScreen.useFurniture "trophy_case"}</li>
 *   <li>수조 구경 창 — 아직 없음 (furniture.yml 에 수조 가구가 없고, 아공간 수조는 본인만 여는 창). 가구가 생기면 여기 경로·좌표를 두고 연결.</li>
 * </ol>
 *
 * <p>방 안 전시 크기는 "방 한 칸 너비(iso.tile)" 배수, 세로 위치는 가구 외곽(hull) 위쪽에서 높이 비율. 가구 그림마다 모양이 달라 대략값이다.
 */
final class HousingShowcaseLayout {
    private HousingShowcaseLayout() {}

    // ---------------------------------------------------------------- 방 안 전시 (가구 위에 작게)
    /** 빗자루 거치대: 빗자루 3D 모델 (BroomUiLink.render). 길이 = 칸 너비 × BROOM_LENGTH, 세로로 걸린 모양 (손잡이 위·솔 아래). */
    static final float BROOM_LENGTH = .95f, BROOM_Y = .46f, BROOM_X = 0f, BROOM_YAW = 0f, BROOM_PITCH = 90f, BROOM_SPIN = 0f;
    /** 아티팩트 진열장: 장착 아티팩트 아이콘을 한 줄(최대 ARTIFACT_PER_ROW)씩, 칸 너비 × ARTIFACT_SIZE 크기. */
    static final float ARTIFACT_SIZE = .30f, ARTIFACT_STEP = .33f, ARTIFACT_Y = .34f, ARTIFACT_ROW_STEP = .34f;
    static final int ARTIFACT_PER_ROW = 4, ARTIFACT_ROWS = 2;
    /** 큐디치 트로피장: 주인 큐디치 티어 아이콘 (결투장 티어 PNG 그대로). */
    static final float TIER_SIZE = .50f, TIER_Y = .40f;
    /** 큐디치 티어 PNG (StatsScreen·결투장과 같은 파일, 수정 금지). tier 0~8. */
    static final String TIER_TEXTURE = "magiccodex:textures/gui/kyudichi/ui/tiers/%s.png";
    static final String[] TIER_FILES = {"bronze", "silver", "gold", "platinum", "diamond", "master", "challenger_1", "challenger_2", "challenger_3"};
    static final String[] TIER_NAMES = {"브론즈", "실버", "골드", "플래티넘", "다이아몬드", "마스터", "챌린저 1", "챌린저 2", "챌린저 3"};
    static String tierTexture(int tier) { return String.format(TIER_TEXTURE, TIER_FILES[Math.clamp(tier, 0, TIER_FILES.length - 1)]); }

    // ---------------------------------------------------------------- 구경 화면 (납품 대기 — 지금은 기존 창)
    /** 1. 빗자루 거치대 상세 카드: 납품 PNG 경로 (비어 있으면 지금처럼 토스트). */
    static final String BROOM_CARD_TEXTURE = "";
    /** 2. 아티팩트 진열장 구경 창: 납품 PNG 경로 (비어 있으면 아티팩트 도감 구경 모드). 도감 구경 모드 제목·개수 문구. */
    static final String ARTIFACT_VIEW_TEXTURE = "";
    static final String ARTIFACT_VIEW_TITLE = "%s의 아티팩트", ARTIFACT_VIEW_COUNT = "장착 %d개";
    /** 3. 트로피장 전적 카드: 납품 PNG 경로 (비어 있으면 결투장 로비 전적 탭). */
    static final String TROPHY_CARD_TEXTURE = "";
    /** 4. 수조 구경 창: 납품 PNG 경로 (가구·서버 경로가 생기면). */
    static final String FISH_VIEW_TEXTURE = "";
}
