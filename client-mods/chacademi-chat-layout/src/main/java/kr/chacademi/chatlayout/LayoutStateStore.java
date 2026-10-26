package kr.chacademi.chatlayout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/** Loads multi-window state or migrates the previous two-window addon layout in memory. */
public final class LayoutStateStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    public LayoutStateStore(Path path) { this.path = Objects.requireNonNull(path, "path"); }

    public LayoutState load() {
        try {
            if (!Files.exists(path)) return new LayoutState();
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new LayoutState();
            LayoutState state;
            if (root.getAsJsonObject().has("windows")) {
                state = JSON.fromJson(root, LayoutState.class);
                if (state == null) state = new LayoutState();
            } else {
                UiState legacy = JSON.fromJson(root, UiState.class);
                if (legacy == null) legacy = new UiState();
                legacy.sanitize();
                state = migrate(legacy);
            }
            state.sanitize();
            return state;
        } catch (IOException | RuntimeException malformed) {
            return new LayoutState();
        }
    }

    private static LayoutState migrate(UiState legacy) {
        LayoutState state = new LayoutState();
        WindowState upper = new WindowState();
        upper.signature = LayoutState.signature(List.of("전체", "기숙사", "파티", "귓말"));
        upper.x = legacy.upperX;
        upper.y = legacy.upperY;
        upper.width = legacy.upperWidth;
        upper.height = legacy.upperHeight;
        upper.locked = legacy.upperLocked;
        upper.mode = legacy.upper;
        upper.restore = legacy.upperRestore;

        WindowState lower = new WindowState();
        lower.signature = LayoutState.signature(List.of("시스템"));
        lower.x = legacy.lowerX != null ? legacy.lowerX : legacy.upperX;
        lower.y = legacy.lowerY != null ? legacy.lowerY : -1;
        lower.width = legacy.lowerWidth;
        lower.height = legacy.lowerHeight;
        lower.locked = legacy.lowerLocked;
        lower.mode = legacy.lower;
        lower.restore = legacy.lowerRestore;
        lower.systemDefault = true;
        state.windows.add(upper);
        state.windows.add(lower);
        return state;
    }

    public void save(LayoutState state) throws IOException {
        Objects.requireNonNull(state, "state").sanitize();
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(state), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
