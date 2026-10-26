package kr.chacademi.npcmodel.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * glTF 2.0 바이너리(GLB) 읽기 + 검증. 실패하면 {@link ModelFormatException} (조용히 오작동하지 않는다).
 *
 * <ul>
 *   <li>지원: 노드 계층·TRS/행렬, 삼각형 메쉬(POSITION·NORMAL·TEXCOORD_0·JOINTS_0·WEIGHTS_0·indices), 스킨(inverse bind),
 *       baseColor 텍스처·색, alphaMode/cutoff/doubleSided, T/R/S 애니메이션(LINEAR·STEP·CUBICSPLINE)</li>
 *   <li>거부: 외부/데이터 URI, extensionsRequired, sparse accessor, 범위 밖 인덱스, 상한 초과, 순환 계층</li>
 *   <li>무시 + 진단: 모프 타깃·weights 애니메이션(후속 범위), 삼각형이 아닌 primitive, JOINTS_1(5개 이상 영향), 기타 확장</li>
 * </ul>
 */
public final class GlbReader {
    private static final int MAGIC = 0x46546C67, CHUNK_JSON = 0x4E4F534A, CHUNK_BIN = 0x004E4942;
    /** 렌더 결과에 영향이 적어 "무시" 진단만 남기는 확장. */
    private static final Set<String> SOFT_EXT = Set.of("KHR_materials_volume", "KHR_materials_specular", "KHR_materials_ior",
            "KHR_materials_emissive_strength", "KHR_texture_transform", "FB_ngon_encoding", "KHR_materials_transmission",
            "KHR_materials_clearcoat", "KHR_materials_sheen", "KHR_materials_unlit");

    private final ModelLimits limits;
    private final List<String> diag = new ArrayList<>();
    private Map<String, Object> js;
    private ByteBuffer bin;
    private long keyframeFloats;

    private GlbReader(ModelLimits limits) {
        this.limits = limits;
    }

    public static ModelData read(byte[] data, ModelLimits limits) {
        return new GlbReader(limits).parse(data);
    }

    private ModelData parse(byte[] data) {
        if (data.length > limits.maxFileBytes) {
            throw new ModelFormatException("파일이 너무 큼 (" + data.length + " > " + limits.maxFileBytes + " 바이트)");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 20 || b.getInt(0) != MAGIC) {
            throw new ModelFormatException("GLB 파일이 아님");
        }
        if (b.getInt(4) != 2) {
            throw new ModelFormatException("glTF 버전 2만 지원");
        }
        long declared = Integer.toUnsignedLong(b.getInt(8));
        if (declared > data.length) {
            throw new ModelFormatException("파일이 잘림 (선언 " + declared + ", 실제 " + data.length + ")");
        }
        int off = 12;
        String jsonText = null;
        while (off + 8 <= declared) {
            long len = Integer.toUnsignedLong(b.getInt(off));
            int type = b.getInt(off + 4);
            if (off + 8 + len > declared) {
                throw new ModelFormatException("청크가 파일 밖으로 나감");
            }
            if (type == CHUNK_JSON && jsonText == null) {
                jsonText = new String(data, off + 8, (int) len, StandardCharsets.UTF_8);
            } else if (type == CHUNK_BIN && bin == null) {
                bin = ByteBuffer.wrap(data, off + 8, (int) len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            off += 8 + (int) len;
        }
        if (jsonText == null) {
            throw new ModelFormatException("JSON 청크 없음");
        }
        js = Json.obj(Json.parse(jsonText), "glTF");
        if (bin == null) {
            bin = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }
        checkAssetAndExtensions();
        checkBuffers();

        ModelData.Node[] nodes = nodes();
        int[] parent = new int[nodes.length];
        Arrays.fill(parent, -1);
        for (int k = 0; k < nodes.length; k++) {
            for (int c : nodes[k].children) {
                if (c < 0 || c >= nodes.length || c == k) {
                    throw new ModelFormatException("노드 " + k + ": 잘못된 자식 " + c);
                }
                if (parent[c] != -1) {
                    throw new ModelFormatException("노드 " + c + ": 부모가 둘");
                }
                parent[c] = k;
            }
        }
        int[] roots = sceneRoots(nodes.length, parent);
        int[] order = topoOrder(nodes, roots);
        ModelData.Image[] images = images();
        ModelData.Material[] materials = materials(images.length);
        ModelData.Skin[] skins = skins(nodes.length);
        ModelData.MeshNode[] meshNodes = meshNodes(nodes, skins, materials.length);
        Map<String, ModelData.Clip> clips = clips(nodes.length);
        Map<String, Float> rootSpeed = chacaExtras();

        int tris = 0, verts = 0;
        for (ModelData.MeshNode mn : meshNodes) {
            for (ModelData.Primitive p : mn.primitives) {
                tris += p.indices.length / 3;
                verts += p.vertexCount();
            }
        }
        if (meshNodes.length == 0) {
            throw new ModelFormatException("그릴 메쉬가 없음");
        }
        ModelData tmp = new ModelData(nodes, roots, parent, order, meshNodes, skins, materials, images, clips, diag,
                0, 0, rootSpeed, tris, verts);
        float[] range = Skinner.restHeightRange(tmp);
        return new ModelData(nodes, roots, parent, order, meshNodes, skins, materials, images, clips,
                List.copyOf(diag), range[0], range[1], rootSpeed, tris, verts);
    }

    private void checkAssetAndExtensions() {
        Map<String, Object> asset = Json.obj(js.get("asset"), "asset");
        String ver = Json.str(asset, "version", "");
        if (!ver.startsWith("2.")) {
            throw new ModelFormatException("asset.version 이 2.x 아님: " + ver);
        }
        List<Object> req = Json.arr(js.get("extensionsRequired"), "extensionsRequired");
        if (!req.isEmpty()) {
            throw new ModelFormatException("필수 확장 미지원: " + req);
        }
        for (Object e : Json.arr(js.get("extensionsUsed"), "extensionsUsed")) {
            diag.add((SOFT_EXT.contains(String.valueOf(e)) ? "확장 무시(표현 영향 적음): " : "확장 무시(미지원): ") + e);
        }
    }

    private void checkBuffers() {
        List<Object> buffers = Json.arr(js.get("buffers"), "buffers");
        if (buffers.size() > 1) {
            throw new ModelFormatException("버퍼는 GLB 내장 1개만 허용");
        }
        for (Object o : buffers) {
            Map<String, Object> bf = Json.obj(o, "buffer");
            if (bf.containsKey("uri")) {
                throw new ModelFormatException("외부/데이터 URI 버퍼 거부");
            }
            if (Json.num(bf, "byteLength", 0) > bin.capacity()) {
                throw new ModelFormatException("버퍼 길이가 BIN 청크보다 김");
            }
        }
    }

    // ------------------------------------------------------------------ 노드

    private ModelData.Node[] nodes() {
        List<Object> list = Json.arr(js.get("nodes"), "nodes");
        if (list.size() > limits.maxNodes) {
            throw new ModelFormatException("노드가 너무 많음: " + list.size());
        }
        ModelData.Node[] out = new ModelData.Node[list.size()];
        for (int k = 0; k < out.length; k++) {
            Map<String, Object> n = Json.obj(list.get(k), "node " + k);
            List<Object> ch = Json.arr(n.get("children"), "children");
            int[] children = new int[ch.size()];
            for (int c = 0; c < children.length; c++) {
                children[c] = asIndex(ch.get(c), "children");
            }
            float[] t = {0, 0, 0}, r = {0, 0, 0, 1}, s = {1, 1, 1};
            if (n.containsKey("matrix")) {
                float[] m = Json.floats(n.get("matrix"), 16, "matrix");
                MathUtil.decompose(m, t, r, s);
            } else {
                if (n.containsKey("translation")) {
                    t = Json.floats(n.get("translation"), 3, "translation");
                }
                if (n.containsKey("rotation")) {
                    r = Json.floats(n.get("rotation"), 4, "rotation");
                    MathUtil.normalizeQuat(r, 0);
                }
                if (n.containsKey("scale")) {
                    s = Json.floats(n.get("scale"), 3, "scale");
                }
            }
            out[k] = new ModelData.Node(Json.str(n, "name", "node" + k), children, t, r, s);
        }
        return out;
    }

    private int[] sceneRoots(int count, int[] parent) {
        List<Object> scenes = Json.arr(js.get("scenes"), "scenes");
        if (!scenes.isEmpty()) {
            int si = Json.integer(js, "scene", 0);
            if (si < 0 || si >= scenes.size()) {
                throw new ModelFormatException("scene 번호 범위 밖");
            }
            List<Object> rs = Json.arr(Json.obj(scenes.get(si), "scene").get("nodes"), "scene.nodes");
            int[] roots = new int[rs.size()];
            for (int k = 0; k < roots.length; k++) {
                roots[k] = asIndex(rs.get(k), "scene.nodes");
                if (roots[k] >= count || parent[roots[k]] != -1) {
                    throw new ModelFormatException("장면 최상위 노드가 잘못됨: " + roots[k]);
                }
            }
            return roots;
        }
        return java.util.stream.IntStream.range(0, count).filter(k -> parent[k] == -1).toArray();
    }

    private int[] topoOrder(ModelData.Node[] nodes, int[] roots) {
        int[] order = new int[nodes.length];
        boolean[] seen = new boolean[nodes.length];
        int n = 0;
        int[] stack = new int[nodes.length + 1];
        // 장면 밖 노드도 계산되게 부모 없는 노드 전부를 시작점으로
        List<Integer> starts = new ArrayList<>();
        for (int r : roots) {
            starts.add(r);
        }
        boolean[] isChild = new boolean[nodes.length];
        for (ModelData.Node nd : nodes) {
            for (int c : nd.children) {
                isChild[c] = true;
            }
        }
        for (int k = 0; k < nodes.length; k++) {
            if (!isChild[k] && !starts.contains(k)) {
                starts.add(k);
            }
        }
        for (int st : starts) {
            int sp = 0;
            stack[sp++] = st;
            while (sp > 0) {
                int cur = stack[--sp];
                if (seen[cur]) {
                    throw new ModelFormatException("노드 계층에 순환이 있음");
                }
                seen[cur] = true;
                order[n++] = cur;
                for (int c : nodes[cur].children) {
                    if (sp >= stack.length) {
                        throw new ModelFormatException("노드 계층에 순환이 있음");
                    }
                    stack[sp++] = c;
                }
            }
        }
        if (n != nodes.length) {
            throw new ModelFormatException("노드 계층에 순환이 있음");
        }
        return order;
    }

    // ------------------------------------------------------------------ 그림·재질

    private ModelData.Image[] images() {
        List<Object> list = Json.arr(js.get("images"), "images");
        if (list.size() > limits.maxImages) {
            throw new ModelFormatException("그림이 너무 많음: " + list.size());
        }
        ModelData.Image[] out = new ModelData.Image[list.size()];
        for (int k = 0; k < out.length; k++) {
            Map<String, Object> im = Json.obj(list.get(k), "image");
            if (im.containsKey("uri")) {
                throw new ModelFormatException("그림 " + k + ": 외부/데이터 URI 거부 (GLB 내장만 허용)");
            }
            int bv = Json.requireInt(im, "bufferView");
            ByteBuffer v = view(bv);
            byte[] bytes = new byte[v.remaining()];
            v.get(bytes);
            String mime = Json.str(im, "mimeType", "");
            if (!mime.equals("image/png") && !mime.equals("image/jpeg")) {
                throw new ModelFormatException("그림 " + k + ": PNG/JPEG 만 지원 (" + mime + ")");
            }
            int[] wh = imageSize(bytes, mime);
            if (wh[0] <= 0 || wh[1] <= 0 || wh[0] > limits.maxImageSide || wh[1] > limits.maxImageSide) {
                throw new ModelFormatException("그림 " + k + ": 크기 " + wh[0] + "x" + wh[1] + " 허용 범위 밖");
            }
            out[k] = new ModelData.Image(mime, bytes);
        }
        return out;
    }

    /** 헤더만 읽어 그림 크기를 얻는다 (디코딩 전 상한 검사). */
    static int[] imageSize(byte[] b, String mime) {
        if (mime.equals("image/png")) {
            if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P') {
                throw new ModelFormatException("PNG 헤더가 깨짐");
            }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            return new int[]{bb.getInt(16), bb.getInt(20)};
        }
        int i = 2;
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            throw new ModelFormatException("JPEG 헤더가 깨짐");
        }
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new ModelFormatException("JPEG 구조가 깨짐");
            }
            int marker = b[i + 1] & 0xFF;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            i += 2 + len;
        }
        throw new ModelFormatException("JPEG 크기를 찾지 못함");
    }

    private ModelData.Material[] materials(int imageCount) {
        List<Object> tex = Json.arr(js.get("textures"), "textures");
        int[] texToImage = new int[tex.size()];
        for (int k = 0; k < texToImage.length; k++) {
            Map<String, Object> t = Json.obj(tex.get(k), "texture");
            if (t.containsKey("extensions")) {
                diag.add("텍스처 " + k + ": 확장 무시");
            }
            int src = Json.integer(t, "source", -1);
            if (src >= imageCount) {
                throw new ModelFormatException("텍스처 " + k + ": 그림 번호 범위 밖");
            }
            texToImage[k] = src;
        }
        List<Object> list = Json.arr(js.get("materials"), "materials");
        ModelData.Material[] out = new ModelData.Material[list.size()];
        for (int k = 0; k < out.length; k++) {
            Map<String, Object> m = Json.obj(list.get(k), "material");
            Map<String, Object> pbr = m.containsKey("pbrMetallicRoughness") ? Json.obj(m.get("pbrMetallicRoughness"), "pbr") : Map.of();
            float[] color = pbr.containsKey("baseColorFactor") ? Json.floats(pbr.get("baseColorFactor"), 4, "baseColorFactor") : new float[]{1, 1, 1, 1};
            int image = -1;
            if (pbr.containsKey("baseColorTexture")) {
                Map<String, Object> ti = Json.obj(pbr.get("baseColorTexture"), "baseColorTexture");
                int t = Json.requireInt(ti, "index");
                if (t < 0 || t >= texToImage.length) {
                    throw new ModelFormatException("재질 " + k + ": 텍스처 번호 범위 밖");
                }
                if (Json.integer(ti, "texCoord", 0) != 0) {
                    diag.add("재질 " + k + ": TEXCOORD_1 이상은 미지원 → TEXCOORD_0 사용");
                }
                image = texToImage[t];
            }
            String am = Json.str(m, "alphaMode", "OPAQUE");
            int mode = switch (am) {
                case "OPAQUE" -> ModelData.ALPHA_OPAQUE;
                case "MASK" -> ModelData.ALPHA_MASK;
                case "BLEND" -> ModelData.ALPHA_BLEND;
                default -> throw new ModelFormatException("재질 " + k + ": alphaMode " + am);
            };
            float cutoff = (float) Json.num(m, "alphaCutoff", 0.5);
            if (mode == ModelData.ALPHA_MASK && Math.abs(cutoff - 0.1f) > 0.05f) {
                diag.add("재질 " + k + ": alphaCutoff " + cutoff + " → 마인크래프트 컷아웃 기준(0.1)으로 그려짐");
            }
            for (String unsupported : new String[]{"normalTexture", "occlusionTexture", "emissiveTexture"}) {
                if (m.containsKey(unsupported)) {
                    diag.add("재질 " + k + ": " + unsupported + " 무시 (바닐라 조명 사용)");
                }
            }
            out[k] = new ModelData.Material(image, color, mode, cutoff, Boolean.TRUE.equals(m.get("doubleSided")));
        }
        return out;
    }

    // ------------------------------------------------------------------ 스킨·메쉬

    private ModelData.Skin[] skins(int nodeCount) {
        List<Object> list = Json.arr(js.get("skins"), "skins");
        ModelData.Skin[] out = new ModelData.Skin[list.size()];
        for (int k = 0; k < out.length; k++) {
            Map<String, Object> s = Json.obj(list.get(k), "skin");
            List<Object> jl = Json.arr(s.get("joints"), "joints");
            if (jl.isEmpty() || jl.size() > limits.maxJoints) {
                throw new ModelFormatException("스킨 " + k + ": 관절 수 " + jl.size() + " 허용 범위 밖");
            }
            int[] joints = new int[jl.size()];
            for (int j = 0; j < joints.length; j++) {
                joints[j] = asIndex(jl.get(j), "joints");
                if (joints[j] >= nodeCount) {
                    throw new ModelFormatException("스킨 " + k + ": 관절 노드 범위 밖");
                }
            }
            float[] ibm;
            if (s.containsKey("inverseBindMatrices")) {
                Accessor a = accessor(Json.requireInt(s, "inverseBindMatrices"));
                if (!a.type.equals("MAT4") || a.count != joints.length) {
                    throw new ModelFormatException("스킨 " + k + ": inverseBindMatrices 형식/개수 불일치");
                }
                ibm = a.floats();
            } else {
                ibm = new float[16 * joints.length];
                for (int j = 0; j < joints.length; j++) {
                    MathUtil.identity(ibm, j * 16);
                }
            }
            for (float f : ibm) {
                if (!Float.isFinite(f)) {
                    throw new ModelFormatException("스킨 " + k + ": inverseBindMatrices 에 NaN/무한");
                }
            }
            out[k] = new ModelData.Skin(joints, ibm);
        }
        return out;
    }

    private ModelData.MeshNode[] meshNodes(ModelData.Node[] nodes, ModelData.Skin[] skins, int materialCount) {
        List<Object> meshes = Json.arr(js.get("meshes"), "meshes");
        if (meshes.size() > limits.maxMeshes) {
            throw new ModelFormatException("메쉬가 너무 많음: " + meshes.size());
        }
        List<Object> nodeList = Json.arr(js.get("nodes"), "nodes");
        Map<Integer, ModelData.Primitive[]> cache = new LinkedHashMap<>();
        List<ModelData.MeshNode> out = new ArrayList<>();
        int prims = 0;
        long verts = 0, idx = 0;
        for (int k = 0; k < nodeList.size(); k++) {
            Map<String, Object> n = Json.obj(nodeList.get(k), "node");
            if (!n.containsKey("mesh")) {
                continue;
            }
            int mi = Json.requireInt(n, "mesh");
            if (mi < 0 || mi >= meshes.size()) {
                throw new ModelFormatException("노드 " + k + ": 메쉬 번호 범위 밖");
            }
            int si = Json.integer(n, "skin", -1);
            if (si >= skins.length) {
                throw new ModelFormatException("노드 " + k + ": 스킨 번호 범위 밖");
            }
            ModelData.Primitive[] ps = cache.get(mi * 1000 + (si + 1));
            if (ps == null) {
                ps = primitives(Json.obj(meshes.get(mi), "mesh"), si < 0 ? null : skins[si], materialCount, mi);
                cache.put(mi * 1000 + (si + 1), ps);
                for (ModelData.Primitive p : ps) {
                    prims++;
                    verts += p.vertexCount();
                    idx += p.indices.length;
                }
            }
            if (prims > limits.maxPrimitives || verts > limits.maxVertices || idx > limits.maxIndices) {
                throw new ModelFormatException("메쉬 상한 초과 (primitive " + prims + ", 정점 " + verts + ", 인덱스 " + idx + ")");
            }
            if (ps.length > 0) {
                out.add(new ModelData.MeshNode(k, si, ps));
            }
        }
        return out.toArray(new ModelData.MeshNode[0]);
    }

    private ModelData.Primitive[] primitives(Map<String, Object> mesh, ModelData.Skin skin, int materialCount, int meshIndex) {
        List<ModelData.Primitive> out = new ArrayList<>();
        List<Object> list = Json.arr(mesh.get("primitives"), "primitives");
        for (int pi = 0; pi < list.size(); pi++) {
            Map<String, Object> p = Json.obj(list.get(pi), "primitive");
            String where = "메쉬 " + meshIndex + " primitive " + pi;
            int mode = Json.integer(p, "mode", 4);
            if (mode != 4) {
                diag.add(where + ": 삼각형 아님(mode " + mode + ") → 건너뜀");
                continue;
            }
            if (!Json.arr(p.get("targets"), "targets").isEmpty()) {
                diag.add(where + ": 모프 타깃 무시 (얼굴 모프는 후속 범위)");
            }
            Map<String, Object> attr = Json.obj(p.get("attributes"), "attributes");
            if (!attr.containsKey("POSITION")) {
                throw new ModelFormatException(where + ": POSITION 없음");
            }
            Accessor pos = accessor(Json.requireInt(attr, "POSITION"));
            if (!pos.type.equals("VEC3") || pos.componentType != 5126) {
                throw new ModelFormatException(where + ": POSITION 은 float VEC3 이어야 함");
            }
            int vc = pos.count;
            float[] positions = pos.floats();
            checkFinite(positions, where + " POSITION");
            float[] normals = null;
            if (attr.containsKey("NORMAL")) {
                Accessor a = accessor(Json.requireInt(attr, "NORMAL"));
                if (!a.type.equals("VEC3") || a.count != vc) {
                    throw new ModelFormatException(where + ": NORMAL 형식/개수 불일치");
                }
                normals = a.floats();
            }
            float[] uvs;
            if (attr.containsKey("TEXCOORD_0")) {
                Accessor a = accessor(Json.requireInt(attr, "TEXCOORD_0"));
                if (!a.type.equals("VEC2") || a.count != vc) {
                    throw new ModelFormatException(where + ": TEXCOORD_0 형식/개수 불일치");
                }
                uvs = a.floats();
            } else {
                uvs = new float[vc * 2];
            }
            int[] indices;
            if (p.containsKey("indices")) {
                Accessor a = accessor(Json.requireInt(p, "indices"));
                if (!a.type.equals("SCALAR") || a.componentType == 5126) {
                    throw new ModelFormatException(where + ": indices 형식 오류");
                }
                indices = a.ints();
            } else {
                indices = java.util.stream.IntStream.range(0, vc).toArray();
            }
            if (indices.length % 3 != 0) {
                throw new ModelFormatException(where + ": 인덱스 수가 3의 배수가 아님");
            }
            for (int v : indices) {
                if (v < 0 || v >= vc) {
                    throw new ModelFormatException(where + ": 인덱스가 정점 범위 밖");
                }
            }
            int[] joints = null;
            float[] weights = null;
            if (skin != null && attr.containsKey("JOINTS_0") && attr.containsKey("WEIGHTS_0")) {
                Accessor ja = accessor(Json.requireInt(attr, "JOINTS_0"));
                Accessor wa = accessor(Json.requireInt(attr, "WEIGHTS_0"));
                if (!ja.type.equals("VEC4") || !wa.type.equals("VEC4") || ja.count != vc || wa.count != vc) {
                    throw new ModelFormatException(where + ": JOINTS_0/WEIGHTS_0 형식/개수 불일치");
                }
                if (ja.componentType != 5121 && ja.componentType != 5123) {
                    throw new ModelFormatException(where + ": JOINTS_0 는 u8/u16 이어야 함");
                }
                joints = ja.ints();
                weights = wa.floats();
                for (int v = 0; v < vc; v++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++) {
                        int j = joints[v * 4 + k];
                        float w = weights[v * 4 + k];
                        if (!(w >= 0) || !Float.isFinite(w)) {
                            throw new ModelFormatException(where + ": 음수/NaN 웨이트");
                        }
                        if (j >= skin.joints.length) {
                            if (w > 0) {
                                throw new ModelFormatException(where + ": 관절 번호가 스킨 범위 밖");
                            }
                            joints[v * 4 + k] = 0;
                        }
                        sum += w;
                    }
                    if (sum <= 1e-6f) {
                        weights[v * 4] = 1; // 웨이트 0 정점: 첫 관절에 붙인다
                        sum = 1;
                    }
                    for (int k = 0; k < 4; k++) {
                        weights[v * 4 + k] /= sum;
                    }
                }
                if (attr.containsKey("JOINTS_1")) {
                    diag.add(where + ": JOINTS_1(정점당 5개 이상 영향) 무시 — 4개만 사용");
                }
            } else if (skin != null) {
                diag.add(where + ": 스킨 노드인데 JOINTS_0/WEIGHTS_0 없음 → 고정 메쉬");
            }
            int mat = Json.integer(p, "material", -1);
            if (mat >= materialCount) {
                throw new ModelFormatException(where + ": 재질 번호 범위 밖");
            }
            if (normals == null) {
                normals = faceNormals(positions, indices);
                diag.add(where + ": NORMAL 없음 → 면 법선 계산");
            }
            out.add(new ModelData.Primitive(positions, normals, uvs, joints, weights, indices, mat));
        }
        return out.toArray(new ModelData.Primitive[0]);
    }

    private static float[] faceNormals(float[] p, int[] idx) {
        float[] n = new float[p.length];
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            float ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
            float vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int v : new int[]{a, b, c}) {
                n[v] += nx;
                n[v + 1] += ny;
                n[v + 2] += nz;
            }
        }
        for (int v = 0; v < n.length; v += 3) {
            float l = MathUtil.len(n[v], n[v + 1], n[v + 2]);
            if (l > 1e-12f) {
                n[v] /= l;
                n[v + 1] /= l;
                n[v + 2] /= l;
            } else {
                n[v + 1] = 1;
            }
        }
        return n;
    }

    private static void checkFinite(float[] f, String what) {
        for (float v : f) {
            if (!Float.isFinite(v)) {
                throw new ModelFormatException(what + ": NaN/무한 값");
            }
        }
    }

    // ------------------------------------------------------------------ 애니메이션

    private Map<String, ModelData.Clip> clips(int nodeCount) {
        List<Object> list = Json.arr(js.get("animations"), "animations");
        if (list.size() > limits.maxClips) {
            throw new ModelFormatException("애니메이션이 너무 많음: " + list.size());
        }
        Map<String, ModelData.Clip> out = new LinkedHashMap<>();
        for (int k = 0; k < list.size(); k++) {
            Map<String, Object> a = Json.obj(list.get(k), "animation");
            String name = Json.str(a, "name", "clip" + k);
            int bar = name.lastIndexOf('|');
            if (bar >= 0) {
                name = name.substring(bar + 1); // Blender "Armature|Armature|walk"
            }
            List<Object> samplers = Json.arr(a.get("samplers"), "samplers");
            List<ModelData.Channel> channels = new ArrayList<>();
            float duration = 0;
            for (Object co : Json.arr(a.get("channels"), "channels")) {
                Map<String, Object> c = Json.obj(co, "channel");
                Map<String, Object> target = Json.obj(c.get("target"), "target");
                if (!target.containsKey("node")) {
                    continue;
                }
                int node = Json.requireInt(target, "node");
                if (node < 0 || node >= nodeCount) {
                    throw new ModelFormatException("애니메이션 '" + name + "': 대상 노드 범위 밖");
                }
                String path = Json.str(target, "path", "");
                int pathId;
                int comps;
                switch (path) {
                    case "translation" -> { pathId = ModelData.PATH_T; comps = 3; }
                    case "rotation" -> { pathId = ModelData.PATH_R; comps = 4; }
                    case "scale" -> { pathId = ModelData.PATH_S; comps = 3; }
                    case "weights" -> {
                        diag.add("애니메이션 '" + name + "': 모프 weights 채널 무시");
                        continue;
                    }
                    default -> throw new ModelFormatException("애니메이션 '" + name + "': 경로 " + path);
                }
                int si = Json.requireInt(c, "sampler");
                if (si < 0 || si >= samplers.size()) {
                    throw new ModelFormatException("애니메이션 '" + name + "': 샘플러 번호 범위 밖");
                }
                Map<String, Object> s = Json.obj(samplers.get(si), "sampler");
                String interp = Json.str(s, "interpolation", "LINEAR");
                int it = switch (interp) {
                    case "LINEAR" -> ModelData.INTERP_LINEAR;
                    case "STEP" -> ModelData.INTERP_STEP;
                    case "CUBICSPLINE" -> ModelData.INTERP_CUBIC;
                    default -> throw new ModelFormatException("보간 방식 " + interp);
                };
                Accessor in = accessor(Json.requireInt(s, "input"));
                Accessor outA = accessor(Json.requireInt(s, "output"));
                float[] times = in.floats();
                float[] values = outA.floats();
                int per = comps * (it == ModelData.INTERP_CUBIC ? 3 : 1);
                if (!in.type.equals("SCALAR") || times.length == 0 || values.length != times.length * per) {
                    throw new ModelFormatException("애니메이션 '" + name + "': 키 개수 불일치");
                }
                for (int t = 0; t < times.length; t++) {
                    if (!Float.isFinite(times[t]) || times[t] < 0 || (t > 0 && times[t] < times[t - 1])) {
                        throw new ModelFormatException("애니메이션 '" + name + "': 시간이 증가하지 않음");
                    }
                }
                checkFinite(values, "애니메이션 '" + name + "'");
                keyframeFloats += times.length + values.length;
                if (keyframeFloats > limits.maxKeyframeFloats) {
                    throw new ModelFormatException("애니메이션 키가 너무 많음");
                }
                duration = Math.max(duration, times[times.length - 1]);
                channels.add(new ModelData.Channel(node, pathId, it, times, values));
            }
            if (!channels.isEmpty()) {
                if (out.containsKey(name)) {
                    diag.add("애니메이션 이름 중복 '" + name + "' → 뒤의 것은 '" + name + "#" + k + "'");
                    name = name + "#" + k;
                }
                out.put(name, new ModelData.Clip(name, duration, channels.toArray(new ModelData.Channel[0])));
            }
        }
        return out;
    }

    private Map<String, Float> chacaExtras() {
        Map<String, Float> out = new LinkedHashMap<>();
        try {
            Map<String, Object> asset = Json.obj(js.get("asset"), "asset");
            if (!(asset.get("extras") instanceof Map<?, ?>)) {
                return out;
            }
            Object chaca = Json.obj(asset.get("extras"), "extras").get("chaca");
            if (!(chaca instanceof Map<?, ?>)) {
                return out;
            }
            Object clips = Json.obj(chaca, "chaca").get("clips");
            if (!(clips instanceof Map<?, ?>)) {
                return out;
            }
            for (Map.Entry<String, Object> e : Json.obj(clips, "clips").entrySet()) {
                if (e.getValue() instanceof Map<?, ?>) {
                    double v = Json.num(Json.obj(e.getValue(), "clip"), "rootSpeed", 0);
                    if (v > 0 && v < 50) {
                        out.put(e.getKey(), (float) v);
                    }
                }
            }
        } catch (ModelFormatException ex) {
            diag.add("asset.extras.chaca 무시: " + ex.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ accessor

    private int asIndex(Object o, String what) {
        if (!(o instanceof Double d) || d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) {
            throw new ModelFormatException(what + ": 번호가 아님");
        }
        return d.intValue();
    }

    private ByteBuffer view(int index) {
        List<Object> views = Json.arr(js.get("bufferViews"), "bufferViews");
        if (index < 0 || index >= views.size()) {
            throw new ModelFormatException("bufferView 번호 범위 밖: " + index);
        }
        Map<String, Object> v = Json.obj(views.get(index), "bufferView");
        if (Json.integer(v, "buffer", 0) != 0) {
            throw new ModelFormatException("bufferView 가 GLB 버퍼 밖을 가리킴");
        }
        long off = (long) Json.num(v, "byteOffset", 0);
        long len = (long) Json.num(v, "byteLength", -1);
        if (off < 0 || len < 0 || off + len > bin.capacity()) {
            throw new ModelFormatException("bufferView " + index + " 범위가 버퍼 밖");
        }
        ByteBuffer b = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.position((int) off).limit((int) (off + len));
        return b.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private int viewStride(int index) {
        Map<String, Object> v = Json.obj(Json.arr(js.get("bufferViews"), "bufferViews").get(index), "bufferView");
        return Json.integer(v, "byteStride", 0);
    }

    private Accessor accessor(int index) {
        List<Object> list = Json.arr(js.get("accessors"), "accessors");
        if (index < 0 || index >= list.size()) {
            throw new ModelFormatException("accessor 번호 범위 밖: " + index);
        }
        Map<String, Object> a = Json.obj(list.get(index), "accessor");
        if (a.containsKey("sparse")) {
            throw new ModelFormatException("sparse accessor 미지원");
        }
        String type = Json.str(a, "type", "");
        int comps = switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new ModelFormatException("accessor 형식 " + type + " 미지원");
        };
        int ct = Json.requireInt(a, "componentType");
        int size = switch (ct) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new ModelFormatException("componentType " + ct);
        };
        int count = Json.requireInt(a, "count");
        if (count < 0 || (long) count * comps > 64_000_000L) {
            throw new ModelFormatException("accessor count 범위 밖");
        }
        if (!a.containsKey("bufferView")) {
            throw new ModelFormatException("bufferView 없는 accessor 미지원");
        }
        int bvIndex = Json.requireInt(a, "bufferView");
        ByteBuffer v = view(bvIndex);
        int off = Json.integer(a, "byteOffset", 0);
        int elem = comps * size;
        int stride = viewStride(bvIndex);
        if (stride == 0) {
            stride = elem;
        }
        if (stride < elem || off < 0 || (count > 0 && (long) off + (long) stride * (count - 1) + elem > v.capacity())) {
            throw new ModelFormatException("accessor " + index + " 가 bufferView 밖으로 나감");
        }
        return new Accessor(v, off, stride, ct, comps, count, type, Boolean.TRUE.equals(a.get("normalized")));
    }

    private static final class Accessor {
        final ByteBuffer b;
        final int off, stride, componentType, comps, count;
        final String type;
        final boolean normalized;

        Accessor(ByteBuffer b, int off, int stride, int componentType, int comps, int count, String type, boolean normalized) {
            this.b = b;
            this.off = off;
            this.stride = stride;
            this.componentType = componentType;
            this.comps = comps;
            this.count = count;
            this.type = type;
            this.normalized = normalized;
        }

        float[] floats() {
            float[] out = new float[count * comps];
            for (int e = 0; e < count; e++) {
                int base = off + e * stride;
                for (int c = 0; c < comps; c++) {
                    out[e * comps + c] = switch (componentType) {
                        case 5126 -> b.getFloat(base + c * 4);
                        case 5121 -> normalized ? (b.get(base + c) & 0xFF) / 255f : (b.get(base + c) & 0xFF);
                        case 5123 -> normalized ? (b.getShort(base + c * 2) & 0xFFFF) / 65535f : (b.getShort(base + c * 2) & 0xFFFF);
                        case 5120 -> normalized ? Math.max(b.get(base + c) / 127f, -1f) : b.get(base + c);
                        case 5122 -> normalized ? Math.max(b.getShort(base + c * 2) / 32767f, -1f) : b.getShort(base + c * 2);
                        case 5125 -> (float) Integer.toUnsignedLong(b.getInt(base + c * 4));
                        default -> throw new ModelFormatException("componentType");
                    };
                }
            }
            return out;
        }

        int[] ints() {
            int[] out = new int[count * comps];
            for (int e = 0; e < count; e++) {
                int base = off + e * stride;
                for (int c = 0; c < comps; c++) {
                    long v = switch (componentType) {
                        case 5121 -> b.get(base + c) & 0xFF;
                        case 5123 -> b.getShort(base + c * 2) & 0xFFFF;
                        case 5125 -> Integer.toUnsignedLong(b.getInt(base + c * 4));
                        case 5120 -> b.get(base + c);
                        case 5122 -> b.getShort(base + c * 2);
                        default -> throw new ModelFormatException("정수 accessor 가 아님");
                    };
                    if (v < 0 || v > Integer.MAX_VALUE) {
                        throw new ModelFormatException("인덱스 값 범위 밖");
                    }
                    out[e * comps + c] = (int) v;
                }
            }
            return out;
        }
    }
}
