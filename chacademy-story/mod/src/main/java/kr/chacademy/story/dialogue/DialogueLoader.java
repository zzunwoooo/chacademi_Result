package kr.chacademy.story.dialogue;

import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 대화 내용을 읽는다: 서버가 보낸 내용({@link ServerDialogues})이 있으면 그것, 없으면 config/chaca_dialogue/&lt;id&gt;/dialogue.yml
 * (개발용 /스토리도구, 예전 서버). 내용 검사는 {@link DialogueParser}.
 */
public final class DialogueLoader {
    public static final Pattern ID_PATTERN = DialogueParser.ID_PATTERN;

    private DialogueLoader() {}

    public static Path rootDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("chaca_dialogue");
    }

    public static Path folder(String id) {
        return rootDir().resolve(id);
    }

    public static List<String> listIds() { return listIds(rootDir()); }

    static List<String> listIds(Path root) {
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(root)) try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> Files.isRegularFile(p.resolve("dialogue.yml")))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> ID_PATTERN.matcher(n).matches())
                    .sorted().forEach(ids::add);
        } catch (IOException ignored) {
        }
        for (String id : ServerDialogues.ids()) if (!ids.contains(id)) ids.add(id);
        java.util.Collections.sort(ids);
        return ids;
    }

    public static Dialogue load(String id) throws IOException {
        if (!ID_PATTERN.matcher(id).matches()) throw new IOException("잘못된 대화 id: " + id);
        String server = ServerDialogues.get(id);
        if (server != null) return DialogueParser.parse(id, yaml(server));
        return load(rootDir(), id);
    }

    /** 서버가 보낸 내용이 있거나 로컬 파일이 있는지. */
    public static boolean exists(String id) {
        return ServerDialogues.get(id) != null || Files.isRegularFile(folder(id).resolve("dialogue.yml"));
    }

    private static Object yaml(String text) throws IOException {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new IOException("yml 형식 오류: " + e.getMessage(), e);
        }
    }

    /** Same parser for read-only validation and playback; no progress or command execution. */
    public static Dialogue load(Path rootDir, String id) throws IOException {
        if (!ID_PATTERN.matcher(id).matches()) throw new IOException("잘못된 대화 id: " + id);
        Path file = rootDir.resolve(id).resolve("dialogue.yml");
        if (!Files.isRegularFile(file)) throw new IOException("파일 없음: " + file);
        Object root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
        } catch (RuntimeException e) {
            throw new IOException("yml 형식 오류: " + e.getMessage(), e);
        }
        return DialogueParser.parse(id, root);
    }
    public static List<String> warnings(Path rootDir,String id) throws IOException {
        if(!ID_PATTERN.matcher(id).matches())throw new IOException("Invalid dialogue ID");
        String server=rootDir.equals(rootDir())?ServerDialogues.get(id):null;
        if(server!=null){Object root=yaml(server);return root instanceof java.util.Map<?,?> map?DialogueCommands.warnings(map.get("commands")):List.of();}
        try(Reader reader=Files.newBufferedReader(rootDir.resolve(id).resolve("dialogue.yml"),StandardCharsets.UTF_8)){
            Object root=new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return root instanceof java.util.Map<?,?> map?DialogueCommands.warnings(map.get("commands")):List.of();
        }catch(RuntimeException e){throw new IOException("Invalid dialogue YAML",e);}
    }
}
