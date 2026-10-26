package kr.chacademi.npcmodel.client;

import kr.chacademi.npcmodel.core.AnimationController;
import kr.chacademi.npcmodel.core.ModelData;
import kr.chacademi.npcmodel.core.Pose;
import kr.chacademi.npcmodel.core.Skinner;

/**
 * NPC 한 명의 자세 상태. 모델(메쉬·텍스처·클립)은 공유하고, 여기에는 애니메이션 상태와 변형된 정점만 둔다.
 *
 * <p>거리별 자세 갱신 빈도: 근거리 30Hz · 중거리 15Hz · 원거리 8Hz · 그 너머 3Hz.
 * 프레임당 스키닝 시간 예산({@link NpcModelRenderer})을 넘으면 이번 프레임은 지난 자세를 그대로 그린다.
 * 화면 밖이면 렌더가 불리지 않아 계산도 멈추고, 다시 보이면 게임 시각 기준 자세로 이어진다.</p>
 */
public final class NpcInstance {
    private final ModelManager.LoadedModel model;
    private final AnimationController anim;
    private final Pose pose;
    private final Pose[] lodPose;
    private final float[] world;
    private final float[][] lodWorld;
    private final Skinned[] skinned; // [0] 기본, [1..] LOD
    private float scale = 1;
    private float lastPoseTime = Float.NEGATIVE_INFINITY;
    private boolean released;

    /** 공유 모델 메쉬 노드별 변형 결과. */
    static final class Skinned {
        final float[][][] pos, nrm; // [meshNode][primitive][xyz]
        final float[][] joints;     // [meshNode] 관절 행렬
        boolean valid;

        Skinned(ModelData m) {
            pos = new float[m.meshNodes.length][][];
            nrm = new float[m.meshNodes.length][][];
            joints = new float[m.meshNodes.length][];
            for (int i = 0; i < m.meshNodes.length; i++) {
                ModelData.MeshNode mn = m.meshNodes[i];
                pos[i] = new float[mn.primitives.length][];
                nrm[i] = new float[mn.primitives.length][];
                for (int p = 0; p < mn.primitives.length; p++) {
                    pos[i][p] = new float[mn.primitives[p].positions.length];
                    nrm[i][p] = new float[mn.primitives[p].positions.length];
                }
                if (mn.skin >= 0) {
                    joints[i] = new float[m.skins[mn.skin].joints.length * 16];
                }
            }
        }
    }

    NpcInstance(ModelManager.LoadedModel model, int seed) {
        this.model = model;
        model.refs++;
        ModelData m = model.base;
        var e = model.entry;
        this.anim = new AnimationController(m, model.clips, e.idleClip(), e.idleVariants(), e.walkClip(), e.talkClip(),
                e.greetClip(), e.walkSpeed(), seed * 2654435761L);
        this.pose = new Pose(m.nodes.length);
        this.world = new float[m.nodes.length * 16];
        this.skinned = new Skinned[1 + model.lods.size()];
        this.lodPose = new Pose[model.lods.size()];
        this.lodWorld = new float[model.lods.size()][];
    }

    public ModelManager.LoadedModel model() {
        return model;
    }

    void setScale(float s) {
        this.scale = s;
    }

    private float fixedYaw = Float.NaN;

    /** 서버가 정한 고정 방향 (마인크래프트 yaw). NaN = 엔티티 몸 방향을 따라감. */
    void setFixedYaw(float yaw) {
        this.fixedYaw = yaw;
    }

    public float fixedYaw() {
        return fixedYaw;
    }

    void setServerState(int base, int greetSeq) {
        anim.setServerState(base, greetSeq);
    }

    /** 최종 크기 = models.json scale × 서버 지정 scale. */
    public float totalScale() {
        float h = model.entry.height();
        float base = h > 0 ? h / Math.max(1e-3f, model.restHeight()) : model.entry.scale();
        return base * scale;
    }

    /** 발바닥 기준 모델 꼭대기 (블록). 이름표·머리 위 표시용. */
    public double topHeight() {
        return model.restHeight() * totalScale() + model.entry.nameplateOffset();
    }

    /** 이 거리에서 쓸 LOD (0 = 기본). LOD 파일이 없으면 항상 기본 메쉬. */
    int lodFor(double distance) {
        int lod = 0;
        for (int k = 0; k < model.lodFrom.size(); k++) {
            if (distance >= model.lodFrom.get(k)) {
                lod = k + 1;
            }
        }
        return lod;
    }

    static float interval(double distance, ModelManager.Settings s) {
        if (distance < s.lodNear) {
            return 1f / 30;
        }
        if (distance < s.lodMid) {
            return 1f / 15;
        }
        if (distance < s.lodFar) {
            return 1f / 8;
        }
        return 1f / 3;
    }

    /**
     * 자세 갱신이 필요하면 하고 true. time = 초(게임 시각), speed = 수평 이동 속도(블록/초).
     * budgetOk=false 면 처음 그리는 경우가 아닌 한 건너뛴다.
     */
    boolean update(float time, float speed, double distance, int lod, ModelManager.Settings s, boolean budgetOk) {
        Skinned sk = skinned(lod);
        boolean due = time - lastPoseTime >= interval(distance, s) || time < lastPoseTime;
        if (sk.valid && (!due || !budgetOk)) {
            return false;
        }
        anim.update(time, speed, pose);
        lastPoseTime = time;
        ModelData m;
        Pose p;
        float[] w;
        if (lod == 0) {
            m = model.base;
            p = pose;
            w = world;
        } else {
            m = model.lods.get(lod - 1);
            p = lodPose(lod);
            int[] map = model.lodNodeMap.get(lod - 1);
            for (int k = 0; k < map.length; k++) {
                if (map[k] >= 0) {
                    System.arraycopy(pose.t, map[k] * 3, p.t, k * 3, 3);
                    System.arraycopy(pose.r, map[k] * 4, p.r, k * 4, 4);
                    System.arraycopy(pose.s, map[k] * 3, p.s, k * 3, 3);
                }
            }
            w = lodWorld[lod - 1];
        }
        Skinner.worldMatrices(m, p, w);
        for (int i = 0; i < m.meshNodes.length; i++) {
            ModelData.MeshNode mn = m.meshNodes[i];
            float[] jm = sk.joints[i];
            if (jm != null) {
                Skinner.jointMatrices(m.skins[mn.skin], w, jm);
            }
            for (int pi = 0; pi < mn.primitives.length; pi++) {
                Skinner.skin(mn.primitives[pi], jm, w, mn.node * 16, sk.pos[i][pi], sk.nrm[i][pi]);
            }
        }
        sk.valid = true;
        return true;
    }

    private Pose lodPose(int lod) {
        if (lodPose[lod - 1] == null) {
            ModelData m = model.lods.get(lod - 1);
            lodPose[lod - 1] = new Pose(m.nodes.length);
            lodPose[lod - 1].setRest(m);
            lodWorld[lod - 1] = new float[m.nodes.length * 16];
        }
        return lodPose[lod - 1];
    }

    Skinned skinned(int lod) {
        if (skinned[lod] == null) {
            skinned[lod] = new Skinned(lod == 0 ? model.base : model.lods.get(lod - 1));
        }
        return skinned[lod];
    }

    ModelData data(int lod) {
        return lod == 0 ? model.base : model.lods.get(lod - 1);
    }

    public String debugState() {
        return anim.currentState() + (anim.isMoving() ? " ×" + String.format("%.2f", anim.walkRate()) : "");
    }

    void release() {
        if (!released) {
            released = true;
            model.refs--;
            model.lastReleased = System.currentTimeMillis();
        }
    }
}
