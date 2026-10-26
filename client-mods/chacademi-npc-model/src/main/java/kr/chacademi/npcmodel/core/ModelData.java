package kr.chacademi.npcmodel.core;

import java.util.List;
import java.util.Map;

/**
 * 파싱·검증이 끝난 glTF 모델 (GPU 자원 없음, 여러 NPC가 공유하는 읽기 전용 데이터).
 *
 * <p>좌표는 glTF 그대로(+Y 위, 미터). 정리 도구(normalize_npc_model.py)를 거친 파일은 정면 +Z, 1 = 1블록, 원점 = 발바닥.
 * 정리 안 된 파일도 읽을 수는 있고, 높이·바닥 보정은 {@link #restMinY}/{@link #restMaxY}로 렌더러가 맞춘다.</p>
 */
public final class ModelData {

    public static final int PATH_T = 0, PATH_R = 1, PATH_S = 2;
    public static final int INTERP_LINEAR = 0, INTERP_STEP = 1, INTERP_CUBIC = 2;
    public static final int ALPHA_OPAQUE = 0, ALPHA_MASK = 1, ALPHA_BLEND = 2;

    public final Node[] nodes;
    /** 장면 최상위 노드들. */
    public final int[] roots;
    /** 부모 노드 번호 (최상위는 -1). */
    public final int[] parent;
    /** 부모가 자식보다 먼저 오는 순서 (월드 행렬 계산용). */
    public final int[] order;
    public final MeshNode[] meshNodes;
    public final Skin[] skins;
    public final Material[] materials;
    public final Image[] images;
    public final Map<String, Clip> clips;
    public final List<String> diagnostics;
    /** 기본 자세 기준 높이 범위 (모델 좌표). */
    public final float restMinY, restMaxY;
    /** asset.extras.chaca.clips 의 원래 루트 이동 속도(블록/초, 정리 도구가 기록). 없으면 비어 있음. */
    public final Map<String, Float> clipRootSpeed;
    public final int triangleCount, vertexCount;

    public ModelData(Node[] nodes, int[] roots, int[] parent, int[] order, MeshNode[] meshNodes, Skin[] skins,
                     Material[] materials, Image[] images, Map<String, Clip> clips, List<String> diagnostics,
                     float restMinY, float restMaxY, Map<String, Float> clipRootSpeed, int triangleCount, int vertexCount) {
        this.nodes = nodes;
        this.roots = roots;
        this.parent = parent;
        this.order = order;
        this.meshNodes = meshNodes;
        this.skins = skins;
        this.materials = materials;
        this.images = images;
        this.clips = clips;
        this.diagnostics = diagnostics;
        this.restMinY = restMinY;
        this.restMaxY = restMaxY;
        this.clipRootSpeed = clipRootSpeed;
        this.triangleCount = triangleCount;
        this.vertexCount = vertexCount;
    }

    public int nodeByName(String name) {
        for (int k = 0; k < nodes.length; k++) {
            if (name.equals(nodes[k].name)) {
                return k;
            }
        }
        return -1;
    }

    /** 노드 기본(rest) 변환. matrix 로 주어진 노드는 TRS 로 분해해 둔다 (애니메이션과 섞기 위해). */
    public static final class Node {
        public final String name;
        public final int[] children;
        public final float[] t, r, s;

        public Node(String name, int[] children, float[] t, float[] r, float[] s) {
            this.name = name;
            this.children = children;
            this.t = t;
            this.r = r;
            this.s = s;
        }
    }

    /** 메쉬가 붙은 노드 하나. skin 이 있으면 노드 자신의 변환은 무시된다(glTF 규칙). */
    public static final class MeshNode {
        public final int node;
        public final int skin;
        public final Primitive[] primitives;

        public MeshNode(int node, int skin, Primitive[] primitives) {
            this.node = node;
            this.skin = skin;
            this.primitives = primitives;
        }
    }

    public static final class Primitive {
        public final float[] positions; // xyz
        public final float[] normals;   // xyz (없으면 면 법선으로 계산)
        public final float[] uvs;       // uv (없으면 0)
        public final int[] joints;      // 정점당 4개 (스킨 관절 번호), 스킨 없으면 null
        public final float[] weights;   // 정점당 4개, 합 1로 정규화됨
        public final int[] indices;     // 삼각형 목록
        public final int material;      // -1 = 기본 재질

        public Primitive(float[] positions, float[] normals, float[] uvs, int[] joints, float[] weights, int[] indices, int material) {
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.joints = joints;
            this.weights = weights;
            this.indices = indices;
            this.material = material;
        }

        public int vertexCount() {
            return positions.length / 3;
        }
    }

    public static final class Skin {
        public final int[] joints;          // 노드 번호
        public final float[] inverseBind;   // 관절당 16개 (열 우선)

        public Skin(int[] joints, float[] inverseBind) {
            this.joints = joints;
            this.inverseBind = inverseBind;
        }
    }

    public static final class Material {
        public final int image;            // -1 = 텍스처 없음 (흰색)
        public final float[] baseColor;    // rgba
        public final int alphaMode;
        public final float alphaCutoff;
        public final boolean doubleSided;

        public Material(int image, float[] baseColor, int alphaMode, float alphaCutoff, boolean doubleSided) {
            this.image = image;
            this.baseColor = baseColor;
            this.alphaMode = alphaMode;
            this.alphaCutoff = alphaCutoff;
            this.doubleSided = doubleSided;
        }
    }

    /** 내장 그림 원본 바이트 (JPEG/PNG). 디코딩은 렌더 쪽에서. */
    public static final class Image {
        public final String mime;
        public final byte[] bytes;

        public Image(String mime, byte[] bytes) {
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    public static final class Clip {
        public final String name;
        public final float duration;
        public final Channel[] channels;

        public Clip(String name, float duration, Channel[] channels) {
            this.name = name;
            this.duration = duration;
            this.channels = channels;
        }
    }

    public static final class Channel {
        public final int node;
        public final int path;
        public final int interpolation;
        public final float[] times;
        /** path 에 따라 3(T,S) 또는 4(R) 개씩. CUBIC 이면 [in, value, out] 묶음. */
        public final float[] values;

        public Channel(int node, int path, int interpolation, float[] times, float[] values) {
            this.node = node;
            this.path = path;
            this.interpolation = interpolation;
            this.times = times;
            this.values = values;
        }
    }
}
