package kr.chacademy.cutscene.data;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only validation: no playback, effects, cache replacement or progress writes. */
public final class ContentReload {
    private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,64}");
    @FunctionalInterface public interface Loader { Object load(String id) throws IOException; }
    public record Result(List<String> validIds, Map<String,String> errors) {
        public Result { validIds=List.copyOf(validIds);errors=Collections.unmodifiableMap(new LinkedHashMap<>(errors)); }
    }
    private ContentReload() {}
    public static Result validate(Path root,String filename,Loader loader) {
        var valid=new ArrayList<String>();var errors=new LinkedHashMap<String,String>();
        if (!Files.exists(root)) return new Result(valid,errors);
        try (var paths=Files.list(root)) {
            for (Path dir:paths.sorted().toList()) {
                if (!Files.isRegularFile(dir.resolve(filename))) continue;
                String id=dir.getFileName().toString();
                if (!ID.matcher(id).matches()) {errors.put(id,"폴더 이름은 소문자·숫자·_·- 1~64자여야 합니다.");continue;}
                try {loader.load(id);valid.add(id);}
                catch(IOException|RuntimeException e) {errors.put(id,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            }
        } catch(IOException|RuntimeException e) {errors.put("목록",e.getMessage()==null?"폴더를 읽을 수 없습니다.":e.getMessage());}
        return new Result(valid,errors);
    }
}
