package kr.chacademi.npcmodel.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 마인크래프트 없이 도는 핵심 검사: GLB 읽기·거부, 스키닝, 애니메이션 상태, models.json. */
class CoreTest {

    /** 배포 폴더의 실제 모델 (없으면 그 검사만 건너뜀). */
    static Path dist(String name) {
        for (Path base : new Path[]{Path.of("dist/npc-models"), Path.of("client-mods/chacademi-npc-model/dist/npc-models")}) {
            Path p = base.resolve(name);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 최소 GLB 만들기

    /** 삼각형 1개 + 관절 2개 스킨 + 0→1초 회전 애니메이션. extraJson 으로 키를 덧붙이거나 바꿀 수 있다. */
    static byte[] tinyGlb(String nodesJson, String extra, boolean withSkin) {
        ByteBuffer bin = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        float[] pos = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        for (float f : pos) bin.putFloat(f);           // 0..36
        float[] nrm = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        for (float f : nrm) bin.putFloat(f);           // 36..72
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0); // 72..80 (idx 3개 + 패딩)
        for (int v = 0; v < 3; v++) bin.put((byte) (v == 2 ? 1 : 0)).put((byte) 0).put((byte) 0).put((byte) 0); // 80..92 joints
        for (int v = 0; v < 3; v++) { bin.putFloat(1).putFloat(0).putFloat(0).putFloat(0); } // 92..140 weights
        for (int j = 0; j < 2; j++) for (int k = 0; k < 16; k++) bin.putFloat(k % 5 == 0 ? 1 : 0); // 140..268 ibm
        bin.putFloat(0).putFloat(1);                    // 268..276 times
        bin.putFloat(0).putFloat(0).putFloat(0).putFloat(1); // 276..308 rot key0
        bin.putFloat(0).putFloat(0).putFloat(0.7071068f).putFloat(0.7071068f); // 308..324 rot key1 (z 90도)
        int binLen = 324;
        String skin = withSkin ? ",\"skins\":[{\"joints\":[1,2],\"inverseBindMatrices\":5}]" : "";
        String attrsSkin = withSkin ? ",\"JOINTS_0\":3,\"WEIGHTS_0\":4" : "";
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":" + (nodesJson != null ? nodesJson
                : "[{\"name\":\"root\",\"children\":[1,3]},{\"name\":\"mixamorig:Hips\",\"children\":[2]},{\"name\":\"mixamorig:Head\",\"translation\":[0,1,0]},{\"name\":\"body\",\"mesh\":0" + (withSkin ? ",\"skin\":0" : "") + "}]")
                + ",\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1" + attrsSkin + "},\"indices\":2}]}]"
                + ",\"buffers\":[{\"byteLength\":" + binLen + "}]"
                + ",\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":6},{\"buffer\":0,\"byteOffset\":80,\"byteLength\":12},"
                + "{\"buffer\":0,\"byteOffset\":92,\"byteLength\":48},{\"buffer\":0,\"byteOffset\":140,\"byteLength\":128},"
                + "{\"buffer\":0,\"byteOffset\":268,\"byteLength\":8},{\"buffer\":0,\"byteOffset\":276,\"byteLength\":32}]"
                + ",\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":3,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":5,\"componentType\":5126,\"count\":2,\"type\":\"MAT4\"},"
                + "{\"bufferView\":6,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":7,\"componentType\":5126,\"count\":2,\"type\":\"VEC4\"}]"
                + skin
                + ",\"animations\":[{\"name\":\"Armature|Armature|wave\",\"samplers\":[{\"input\":6,\"output\":7}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"rotation\"}}]}]"
                + (extra == null ? "" : extra) + "}";
        return glb(json, bin.array(), binLen);
    }

    static byte[] glb(String json, byte[] bin, int binLen) {
        byte[] js = json.getBytes(StandardCharsets.UTF_8);
        int jsPad = (4 - js.length % 4) % 4, binPad = (4 - binLen % 4) % 4;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + js.length + jsPad + 8 + binLen + binPad).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(js.length + jsPad).putInt(0x4E4F534A).put(js);
        for (int k = 0; k < jsPad; k++) b.put((byte) ' ');
        b.putInt(binLen + binPad).putInt(0x004E4942).put(bin, 0, binLen);
        return b.array();
    }

    // ---------------------------------------------------------------- GLB

    @Test
    void readsTinyModelAndStripsBlenderPrefix() {
        ModelData m = GlbReader.read(tinyGlb(null, null, true), ModelLimits.DEFAULT);
        assertEquals(1, m.triangleCount);
        assertTrue(m.clips.containsKey("wave"), "Armature|Armature| 접두어 제거");
        assertEquals(0f, m.restMinY, 1e-6);
        assertEquals(2f, m.restMaxY, 1e-6); // Head 관절(0,1,0)에 붙은 정점 (0,1,0) → 높이 2
    }

    @Test
    void skinningFollowsJointRotation() {
        ModelData m = GlbReader.read(tinyGlb(null, null, true), ModelLimits.DEFAULT);
        Pose p = new Pose(m.nodes.length);
        p.setRest(m);
        ClipSampler.sample(m.clips.get("wave"), 1f, p); // Hips z 90도 → (1,0,0) 은 (0,1,0) 으로
        float[] w = new float[m.nodes.length * 16];
        Skinner.worldMatrices(m, p, w);
        ModelData.MeshNode mn = m.meshNodes[0];
        float[] jm = new float[32];
        Skinner.jointMatrices(m.skins[mn.skin], w, jm);
        ModelData.Primitive pr = mn.primitives[0];
        float[] pos = new float[9], nrm = new float[9];
        Skinner.skin(pr, jm, w, mn.node * 16, pos, nrm);
        assertEquals(0f, pos[3], 1e-5);
        assertEquals(1f, pos[4], 1e-5);
        // 세 번째 정점 (0,1,0)은 Head 관절(역바인드 = 단위행렬)에 붙음: Head 월드 = Rz90 이동 (−1,0,0) + Rz90·(0,1,0) → (−2, 0, 0)
        assertEquals(-2f, pos[6], 1e-5);
        assertEquals(0f, pos[7], 1e-5);
    }

    @Test
    void halfwaySampleIsSlerped() {
        ModelData m = GlbReader.read(tinyGlb(null, null, true), ModelLimits.DEFAULT);
        Pose p = new Pose(m.nodes.length);
        p.setRest(m);
        ClipSampler.sample(m.clips.get("wave"), 0.5f, p);
        assertEquals(Math.sin(Math.toRadians(22.5)), p.r[1 * 4 + 2], 1e-4); // z 45도
    }

    @Test
    void rejectsBrokenFiles() {
        byte[] ok = tinyGlb(null, null, true);
        assertThrows(ModelFormatException.class, () -> GlbReader.read(new byte[]{1, 2, 3}, ModelLimits.DEFAULT), "너무 짧음");
        byte[] badMagic = ok.clone();
        badMagic[0] = 0;
        assertThrows(ModelFormatException.class, () -> GlbReader.read(badMagic, ModelLimits.DEFAULT));
        byte[] truncated = java.util.Arrays.copyOf(ok, ok.length - 40);
        assertThrows(ModelFormatException.class, () -> GlbReader.read(truncated, ModelLimits.DEFAULT), "잘린 파일");
        assertThrows(ModelFormatException.class, () -> GlbReader.read(tinyGlb(null, ",\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]", true), ModelLimits.DEFAULT));
        assertThrows(ModelFormatException.class, () -> GlbReader.read(tinyGlb(null, ",\"images\":[{\"uri\":\"../../secret.png\"}]", true), ModelLimits.DEFAULT));
        assertThrows(ModelFormatException.class, () -> GlbReader.read(tinyGlb("[{\"name\":\"a\",\"children\":[1]},{\"name\":\"b\",\"children\":[0],\"mesh\":0}]", null, false), ModelLimits.DEFAULT), "순환");
        ModelLimits tiny = new ModelLimits(ok.length - 1L, 512, 256, 32, 64, 200_000, 1_500_000, 8, 4096, 64, 4_000_000);
        assertThrows(ModelFormatException.class, () -> GlbReader.read(ok, tiny), "크기 상한");
    }

    @Test
    void reportsIgnoredExtensions() {
        ModelData m = GlbReader.read(tinyGlb(null, ",\"extensionsUsed\":[\"KHR_materials_volume\",\"VENDOR_magic\"]", true), ModelLimits.DEFAULT);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.contains("KHR_materials_volume")));
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.contains("VENDOR_magic") && d.contains("미지원")));
    }

    @Test
    void realModelsLoad() throws Exception {
        for (String[] c : new String[][]{{"luka.glb", "1.8", "walk"}, {"ria.glb", "1.7", "walk"}}) {
            Path p = dist(c[0]);
            if (p == null) {
                continue;
            }
            ModelData m = GlbReader.read(Files.readAllBytes(p), ModelLimits.DEFAULT);
            assertEquals(Float.parseFloat(c[1]), m.restMaxY - m.restMinY, 0.01, c[0] + " 키");
            assertEquals(0f, m.restMinY, 0.01, c[0] + " 발바닥 원점");
            assertNotNull(m.clips.get(c[2]), c[0] + " walk 클립");
            assertEquals(65, m.skins[0].joints.length);
        }
    }

    // ---------------------------------------------------------------- 애니메이션 상태

    static AnimationController controller(boolean withTalk) {
        ModelData m = GlbReader.read(tinyGlb(null, null, true), ModelLimits.DEFAULT);
        Map<String, ModelData.Clip> clips = new java.util.HashMap<>(m.clips);
        ModelData.Clip wave = m.clips.get("wave");
        clips.put("idle", rename(wave, "idle"));
        clips.put("walk", rename(wave, "walk"));
        if (withTalk) {
            clips.put("talk", rename(wave, "talk"));
        }
        clips.put("greet", rename(wave, "greet"));
        return new AnimationController(m, clips, "idle", List.of(), "walk", "talk", "greet", 0, 7);
    }

    static ModelData.Clip rename(ModelData.Clip c, String n) {
        return new ModelData.Clip(n, c.duration, c.channels);
    }

    @Test
    void walkNeedsSustainedMovement() {
        AnimationController a = controller(true);
        Pose out = new Pose(4);
        float t = 0;
        for (int k = 0; k < 20; k++, t += 0.05f) a.update(t, 0, out);
        assertEquals("idle", a.currentState());
        a.update(t += 0.05f, 4, out); // 한 틱 튐 → 걷기 아님
        a.update(t += 0.05f, 0, out);
        for (int k = 0; k < 10; k++) a.update(t += 0.05f, 0, out);
        assertFalse(a.isMoving());
        for (int k = 0; k < 20; k++) a.update(t += 0.05f, 2, out);
        assertEquals("walk", a.currentState());
        a.update(t += 0.05f, 0.1f, out); // 잠깐 멈칫 → 유지
        assertTrue(a.isMoving());
        for (int k = 0; k < 40; k++) a.update(t += 0.05f, 0, out);
        assertEquals("idle", a.currentState());
    }

    @Test
    void greetPlaysOncePerSequenceThenTalk() {
        AnimationController a = controller(true);
        Pose out = new Pose(4);
        float t = 0;
        a.setServerState(AnimationController.BASE_IDLE, 5); // 처음 받은 번호는 기준점
        a.update(t, 0, out);
        assertEquals("idle", a.currentState());
        a.setServerState(AnimationController.BASE_TALK, 6);
        a.update(t += 0.05f, 0, out);
        assertEquals("greet", a.currentState());
        for (int k = 0; k < 30; k++) a.update(t += 0.05f, 0, out); // 1초짜리 인사 끝
        assertEquals("talk", a.currentState());
        a.setServerState(AnimationController.BASE_TALK, 6); // 같은 번호 → 다시 안 함
        a.update(t += 0.05f, 0, out);
        assertEquals("talk", a.currentState());
        a.setServerState(AnimationController.BASE_IDLE, 6);
        for (int k = 0; k < 10; k++) a.update(t += 0.05f, 0, out);
        assertEquals("idle", a.currentState());
    }

    @Test
    void missingTalkFallsBackToIdle() {
        AnimationController a = controller(false);
        Pose out = new Pose(4);
        a.setServerState(AnimationController.BASE_TALK, 0);
        a.update(0, 0, out);
        assertEquals("idle", a.currentState());
        assertFalse(a.hasClip("talk"));
    }

    // ---------------------------------------------------------------- models.json

    @Test
    void registryRejectsUnsafePaths() {
        Path dir = Path.of("/tmp/npcmodels").toAbsolutePath();
        String sha = "a".repeat(64);
        ModelRegistry r = ModelRegistry.parse(dir, "{\"models\":{"
                + "\"luka\":{\"file\":\"luka.glb\",\"sha256\":\"" + sha + "\",\"clips\":{\"idle\":\"idle\",\"walk\":\"walk\",\"talk\":\"wait\"}},"
                + "\"evil\":{\"file\":\"../evil.glb\",\"sha256\":\"" + sha + "\"},"
                + "\"abs\":{\"file\":\"/etc/x.glb\",\"sha256\":\"" + sha + "\"},"
                + "\"Bad Id\":{\"file\":\"x.glb\",\"sha256\":\"" + sha + "\"},"
                + "\"nosha\":{\"file\":\"x.glb\"}}}");
        assertEquals(List.of("luka"), List.copyOf(r.models.keySet()));
        assertEquals(4, r.diagnostics.size());
        assertEquals("wait", r.models.get("luka").talkClip());
        assertEquals(dir.resolve("luka.glb"), r.models.get("luka").file().path());
    }

    @Test
    void jsonHandlesEscapesAndRejectsGarbage() {
        Object o = Json.parse("{\"a\":[1,2.5,-3e2],\"b\":\"\\uD55C\\\"\",\"c\":null,\"d\":true}");
        Map<String, Object> m = Json.obj(o, "x");
        assertEquals("한\"", m.get("b"));
        assertEquals(-300.0, ((List<?>) m.get("a")).get(2));
        assertThrows(ModelFormatException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(ModelFormatException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }

    // ---------------------------------------------------------------- 비율 조정 · models.json 저장

    @Test
    void proportionShortensLegsKeepsAnimation() throws Exception {
        Path p = dist("luka.glb");
        if (p == null) {
            return;
        }
        ModelData m = GlbReader.read(Files.readAllBytes(p), ModelLimits.DEFAULT);
        java.util.List<String> diag = new java.util.ArrayList<>();
        assertTrue(Proportion.apply(m, Proportion.Factors.NONE, diag) == m, "배율 1 이면 그대로");
        ModelData q = Proportion.apply(m, new Proportion.Factors(0.8f, 1f, 1f), diag);
        float h0 = m.restMaxY - m.restMinY, h1 = q.restMaxY - q.restMinY;
        assertTrue(h1 < h0 * 0.95f && h1 > h0 * 0.8f, "다리 0.8 → 키가 줄어듦 " + h0 + " → " + h1);
        assertEquals(m.triangleCount, q.triangleCount);
        assertEquals(m.clips.keySet(), q.clips.keySet(), "클립 목록 그대로");
        // 걷기 한 프레임이 문제없이 계산되는지 (NaN 없음)
        Pose pose = new Pose(q.nodes.length);
        pose.setRest(q);
        ClipSampler.sample(q.clips.get("walk"), 0.5f, pose);
        float[] w = new float[q.nodes.length * 16];
        Skinner.worldMatrices(q, pose, w);
        ModelData.MeshNode mn = q.meshNodes[0];
        float[] jm = new float[q.skins[mn.skin].joints.length * 16];
        Skinner.jointMatrices(q.skins[mn.skin], w, jm);
        float[] pos = new float[mn.primitives[0].positions.length], nrm = new float[pos.length];
        Skinner.skin(mn.primitives[0], jm, w, mn.node * 16, pos, nrm);
        for (float f : pos) assertTrue(Float.isFinite(f));
    }

    @Test
    void registryParsesAndSavesProportion() throws Exception {
        Path dir = Files.createTempDirectory("npcmodels");
        String sha = "b".repeat(64);
        Files.writeString(dir.resolve("models.json"), "{\"formatVersion\":1,\"models\":{\"ria\":{\"file\":\"ria.glb\",\"sha256\":\"" + sha
                + "\",\"clips\":{\"idle\":\"wait\"},\"proportion\":{\"legs\":0.82,\"torso\":0.95},\"height\":1.95,\"memo\":\"그대로\"}}}");
        ModelRegistry r = ModelRegistry.load(dir);
        ModelRegistry.Entry e = r.models.get("ria");
        assertEquals(0.82f, e.proportion().legs(), 1e-6);
        assertEquals(1f, e.proportion().arms(), 1e-6);
        assertEquals(1.95f, e.height(), 1e-6);
        r.save(e.with(new Proportion.Factors(0.8f, 0.93f, 0.9f), 2.1f));
        ModelRegistry r2 = ModelRegistry.load(dir);
        assertEquals(0.9f, r2.models.get("ria").proportion().arms(), 1e-6);
        assertEquals(2.1f, r2.models.get("ria").height(), 1e-6);
        assertTrue(Files.readString(dir.resolve("models.json")).contains("\"memo\": \"그대로\""), "다른 키는 보존");
        assertEquals(0, ModelRegistry.parse(dir, "{\"models\":{\"x\":{\"file\":\"x.glb\",\"sha256\":\"" + sha
                + "\",\"proportion\":{\"legs\":0.2}}}}").models.size(), "범위 밖 비율 거부");
    }

    @Test
    void retargetKeepsRotationsDropsMoves() {
        ModelData m = GlbReader.read(tinyGlb(null, null, true), ModelLimits.DEFAULT);
        java.util.List<String> diag = new java.util.ArrayList<>();
        ModelData.Clip c = m.clips.get("wave");
        ModelData.Channel[] chs = {c.channels[0], new ModelData.Channel(c.channels[0].node, ModelData.PATH_T, 0, new float[]{0}, new float[]{5, 5, 5})};
        ModelData.Clip moved = ModelRegistry.retarget(new ModelData.Clip("x", 1, chs), m, m, java.util.Map.of(), diag);
        assertEquals(1, moved.channels.length, "위치 채널은 버림");
        assertEquals(ModelData.PATH_R, moved.channels[0].path);
        // 같은 모델끼리면 뿌리 뼈 보정이 항등이라 값이 그대로
        assertEquals(c.channels[0].values[6], moved.channels[0].values[6], 1e-6);
    }

    @SuppressWarnings("unused")
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
}
