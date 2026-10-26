package kr.chacademi.npcmodel.core;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * NPC 한 명의 애니메이션 상태 (idle / walk / talk / greet).
 *
 * <p>우선순위 (높은 것이 이김):</p>
 * <ol>
 *   <li>걷기 — 실제 이동량으로만 판정(히스테리시스). 걷기 시작하면 인사·대화 동작은 끊긴다.</li>
 *   <li>인사 — 서버 greetSeq 가 바뀔 때 한 번. 끝나면 아래 상태로 돌아감. 같은 번호는 다시 틀지 않음.</li>
 *   <li>대화 — 서버 기본 상태 TALK 인 동안 반복.</li>
 *   <li>대기 — 기본. 15~35초마다 idleVariants 중 하나를 한 번 섞음.</li>
 * </ol>
 * <p>모든 전환은 교차 페이드. 클립이 없으면 대화→대기, 걷기→대기, 대기→기본 자세 순으로 대체하고 인사는 건너뛴다.
 * 시간은 호출자가 주는 초 단위(게임 시계) 기준이라, 화면 밖에서 갱신이 멈췄다가 다시 보여도 그 시각 자세로 이어진다.
 * 애니메이션 재생은 표시용일 뿐 서버 판정(보상·스토리 완료)과 무관하다.</p>
 */
public final class AnimationController {
    public static final int BASE_IDLE = 0, BASE_TALK = 1;

    static final float WALK_ENTER = 0.45f, WALK_EXIT = 0.20f; // 블록/초
    static final float WALK_ENTER_HOLD = 0.15f, WALK_EXIT_HOLD = 0.25f;
    static final float FADE_LOCO = 0.30f, FADE_TALK = 0.35f, FADE_GREET_IN = 0.20f, FADE_GREET_OUT = 0.30f;
    static final float RATE_MIN = 0.6f, RATE_MAX = 1.8f, DEFAULT_WALK_SPEED = 1.3f;

    private final ModelData model;
    private final ModelData.Clip idle, walk, talk, greet;
    private final ModelData.Clip[] variants;
    private final float nativeWalkSpeed;
    private final SplittableRandom rnd;

    private final Pose rest, a, b;

    /** 지금 재생 중 레이어 (to) 와 사라지는 레이어 (from). */
    private Layer to, from;
    private float fadeStart, fadeLen;

    private boolean moving;
    private float aboveSince = -1, belowSince = -1;
    private float smoothSpeed;
    private float walkPhase, walkRate = 1;
    private float lastTime = Float.NaN;

    private int base = BASE_IDLE;
    private int greetSeqSeen = Integer.MIN_VALUE;
    private boolean greetPending;
    private float nextVariantAt;
    private int lastKind = -1;

    private static final int K_REST = 0, K_IDLE = 1, K_WALK = 2, K_TALK = 3, K_GREET = 4, K_VARIANT = 5;

    private static final class Layer {
        final int kind;
        final ModelData.Clip clip;
        final float start;
        final boolean loop;

        Layer(int kind, ModelData.Clip clip, float start, boolean loop) {
            this.kind = kind;
            this.clip = clip;
            this.start = start;
            this.loop = loop;
        }
    }

    public AnimationController(ModelData model, Map<String, ModelData.Clip> clips, String idleName, List<String> variantNames,
                               String walkName, String talkName, String greetName, float walkSpeedOverride, long seed) {
        this.model = model;
        this.idle = clips.get(nn(idleName));
        this.walk = clips.get(nn(walkName));
        this.talk = clips.get(nn(talkName));
        this.greet = clips.get(nn(greetName));
        this.variants = variantNames.stream().map(clips::get).filter(java.util.Objects::nonNull).toArray(ModelData.Clip[]::new);
        float native_ = walkSpeedOverride > 0 ? walkSpeedOverride
                : (walk != null ? model.clipRootSpeed.getOrDefault(walk.name, 0f) : 0f);
        this.nativeWalkSpeed = native_ > 0 ? native_ : DEFAULT_WALK_SPEED;
        this.rnd = new SplittableRandom(seed);
        this.rest = new Pose(model.nodes.length);
        this.rest.setRest(model);
        this.a = new Pose(model.nodes.length);
        this.b = new Pose(model.nodes.length);
    }

    private static String nn(String s) {
        return s == null ? "\u0000" : s;
    }

    /** 서버 상태 반영. greetSeq 가 이전과 다르면 인사 한 번 예약 (처음 받은 값은 기준점으로만 씀). */
    public void setServerState(int baseState, int greetSeq) {
        this.base = baseState == BASE_TALK ? BASE_TALK : BASE_IDLE;
        if (greetSeqSeen == Integer.MIN_VALUE) {
            greetSeqSeen = greetSeq;
            return;
        }
        if (greetSeq != greetSeqSeen) {
            greetSeqSeen = greetSeq;
            greetPending = greet != null;
        }
    }

    /** time: 초(게임 시계), horizontalSpeed: 블록/초. 결과 자세를 out 에 쓴다. */
    public void update(float time, float horizontalSpeed, Pose out) {
        float dt = Float.isNaN(lastTime) ? 0 : Math.max(0, Math.min(0.25f, time - lastTime));
        lastTime = time;
        smoothSpeed += (horizontalSpeed - smoothSpeed) * Math.min(1, dt * 10);
        updateMoving(time, smoothSpeed);
        if (to == null) {
            nextVariantAt = time + 15 + (float) rnd.nextDouble() * 20;
        }

        int kind;
        if (moving && walk != null) {
            kind = K_WALK;
            greetPending = false;
        } else if (greetPending || (to != null && to.kind == K_GREET && !finished(to, time))) {
            kind = K_GREET;
        } else if (base == BASE_TALK && talk != null) {
            kind = K_TALK;
        } else if (to != null && to.kind == K_VARIANT && !finished(to, time)) {
            kind = K_VARIANT;
        } else if (idle != null && variants.length > 0 && time >= nextVariantAt && !moving) {
            kind = K_VARIANT;
        } else {
            kind = idle != null ? K_IDLE : K_REST;
        }

        if (to == null || kind != to.kind || (kind == K_GREET && greetPending) || (kind == K_VARIANT && to.kind != K_VARIANT)) {
            switchTo(kind, time);
        }

        if (kind == K_WALK) {
            float target = Math.max(RATE_MIN, Math.min(RATE_MAX, smoothSpeed / nativeWalkSpeed));
            walkRate += (target - walkRate) * Math.min(1, dt * 4);
            walkPhase += dt * walkRate;
        }

        evaluate(to, time, a);
        float w = fadeLen <= 0 ? 1 : Math.min(1, (time - fadeStart) / fadeLen);
        if (from != null && w < 1) {
            evaluate(from, time, b);
            b.blendToward(a, smoothstep(w));
            out.copyFrom(b);
        } else {
            from = null;
            out.copyFrom(a);
        }
    }

    private void switchTo(int kind, float time) {
        ModelData.Clip clip;
        boolean loop = true;
        float fade;
        switch (kind) {
            case K_WALK -> { clip = walk; fade = FADE_LOCO; }
            case K_TALK -> { clip = talk; fade = FADE_TALK; }
            case K_GREET -> { clip = greet; loop = false; fade = FADE_GREET_IN; greetPending = false; }
            case K_VARIANT -> {
                clip = variants[rnd.nextInt(variants.length)];
                loop = false;
                fade = FADE_LOCO;
                nextVariantAt = time + clip.duration + 15 + (float) rnd.nextDouble() * 20;
            }
            case K_IDLE -> { clip = idle; fade = lastKind == K_GREET ? FADE_GREET_OUT : FADE_LOCO; }
            default -> { clip = null; fade = FADE_LOCO; }
        }
        if (to != null && lastKind == K_GREET && kind != K_GREET) {
            fade = FADE_GREET_OUT;
        }
        // 페이드 도중 또 바뀌어도 지금 주로 보이는 쪽(to)에서 새 동작으로 넘어간다
        from = to;
        to = new Layer(kind, clip, kind == K_WALK ? -1 : time, loop);
        fadeStart = time;
        fadeLen = from == null ? 0 : fade;
        lastKind = kind;
    }

    private boolean finished(Layer l, float time) {
        return l.clip == null || (!l.loop && time - l.start >= l.clip.duration);
    }

    private void evaluate(Layer l, float time, Pose dst) {
        dst.copyFrom(rest);
        if (l == null || l.clip == null) {
            return;
        }
        float t = l.kind == K_WALK ? walkPhase : time - l.start;
        ClipSampler.sample(l.clip, ClipSampler.wrap(l.clip, t, l.loop), dst);
    }

    private void updateMoving(float time, float speed) {
        if (!moving) {
            if (speed > WALK_ENTER) {
                if (aboveSince < 0) {
                    aboveSince = time;
                }
                if (time - aboveSince >= WALK_ENTER_HOLD) {
                    moving = true;
                    belowSince = -1;
                }
            } else {
                aboveSince = -1;
            }
        } else {
            if (speed < WALK_EXIT) {
                if (belowSince < 0) {
                    belowSince = time;
                }
                if (time - belowSince >= WALK_EXIT_HOLD) {
                    moving = false;
                    aboveSince = -1;
                }
            } else {
                belowSince = -1;
            }
        }
    }

    private static float smoothstep(float x) {
        return x * x * (3 - 2 * x);
    }

    // 진단·테스트용
    public boolean isMoving() {
        return moving;
    }

    public String currentState() {
        return to == null ? "none" : switch (to.kind) {
            case K_WALK -> "walk";
            case K_TALK -> "talk";
            case K_GREET -> "greet";
            case K_VARIANT -> "idle-variant";
            case K_IDLE -> "idle";
            default -> "rest";
        };
    }

    public float walkRate() {
        return walkRate;
    }

    public boolean hasClip(String state) {
        return switch (state) {
            case "idle" -> idle != null;
            case "walk" -> walk != null;
            case "talk" -> talk != null;
            case "greet" -> greet != null;
            default -> false;
        };
    }
}
