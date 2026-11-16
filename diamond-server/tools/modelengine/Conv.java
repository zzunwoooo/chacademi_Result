import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * models/*.bbmodel (Modded Entity 형식) → ModelEngine 이 읽는 blueprints/*.bbmodel 로 변환.
 * 사용: java -cp gson.jar Conv.java <models 폴더> <출력 폴더>
 *
 * 바꾸는 것:
 *  - 형식을 Generic(free) 으로, Box UV 표시를 끔 (면마다 UV 값은 원래 들어 있다)
 *  - 애니메이션 이름 animation.<id>.<name> → <name>
 *  - hitbox 뼈: eye_height 판을 지우고 그 높이를 hitbox 뼈의 기준점(origin) y 로 옮김
 *    (ModelEngine 4 는 hitbox 뼈의 상자 1개 + 기준점 y 를 눈높이로 쓴다. 판이 같이 있으면 납작한 히트박스가 될 수 있다)
 *  - 뼈 이름 p_seat → seat (ModelEngine 은 p_ 로 시작하는 뼈를 탑승 자리로 다뤄서, 그냥 모델 일부로 보이게 이름을 바꿈)
 */
public class Conv {
    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), out = Path.of(a[1]);
        Files.createDirectories(out);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "*.bbmodel")) {
            List<Path> files = new ArrayList<>();
            ds.forEach(files::add);
            Collections.sort(files);
            for (Path f : files) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject meta = o.getAsJsonObject("meta");
                meta.addProperty("model_format", "free");
                meta.addProperty("box_uv", false);
                for (String k : List.of("modded_entity_entity_class", "modded_entity_version", "modded_entity_flip_y")) o.remove(k);

                JsonArray elements = o.getAsJsonArray("elements");
                Map<String, JsonObject> byId = new HashMap<>();
                for (JsonElement e : elements) {
                    JsonObject el = e.getAsJsonObject();
                    el.addProperty("box_uv", false);
                    byId.put(el.get("uuid").getAsString(), el);
                }

                StringBuilder note = new StringBuilder();
                Set<String> drop = new HashSet<>();
                fixBones(o.getAsJsonArray("outliner"), byId, drop, note);
                if (!drop.isEmpty()) {
                    JsonArray kept = new JsonArray();
                    for (JsonElement e : elements) if (!drop.contains(e.getAsJsonObject().get("uuid").getAsString())) kept.add(e);
                    o.add("elements", kept);
                }

                List<String> anims = new ArrayList<>();
                if (o.has("animations")) for (JsonElement e : o.getAsJsonArray("animations")) {
                    JsonObject an = e.getAsJsonObject();
                    String name = an.get("name").getAsString();
                    name = name.substring(name.lastIndexOf('.') + 1);
                    an.addProperty("name", name);
                    anims.add(name);
                }
                Files.writeString(out.resolve(f.getFileName()), gson.toJson(o), StandardCharsets.UTF_8);
                System.out.println(f.getFileName() + "  anims=" + anims + note);
            }
        }
    }

    static void fixBones(JsonArray nodes, Map<String, JsonObject> byId, Set<String> drop, StringBuilder note) {
        for (JsonElement n : nodes) {
            if (!n.isJsonObject()) continue;
            JsonObject g = n.getAsJsonObject();
            String name = g.get("name").getAsString();
            JsonArray children = g.getAsJsonArray("children");
            if (name.equals("p_seat")) { g.addProperty("name", "seat"); note.append("  [p_seat→seat]"); }
            if (name.equals("hitbox")) {
                JsonArray kept = new JsonArray();
                double eye = -1;
                for (JsonElement c : children) {
                    JsonObject el = c.isJsonPrimitive() ? byId.get(c.getAsString()) : null;
                    if (el != null && el.get("name").getAsString().equals("eye_height")) {
                        eye = el.getAsJsonArray("from").get(1).getAsDouble();
                        drop.add(el.get("uuid").getAsString());
                    } else kept.add(c);
                }
                g.add("children", kept);
                if (eye > 0) {
                    JsonArray origin = new JsonArray();
                    origin.add(0); origin.add(eye); origin.add(0);
                    g.add("origin", origin);
                    note.append("  [hitbox 눈높이 ").append(eye).append("px, 상자 ").append(kept.size()).append("개]");
                }
                children = kept;
            }
            fixBones(children, byId, drop, note);
        }
    }
}
