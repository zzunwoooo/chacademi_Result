package kr.chacademi.chatlayout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small addon-only configuration file. No ChatPlus filters or messages are serialized. */
public final class UiStateStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    public UiStateStore(Path path) { this.path = path; }

    public UiState load() {
        try {
            UiState state = Files.exists(path) ? JSON.fromJson(Files.readString(path), UiState.class) : new UiState();
            if (state == null) state = new UiState();
            state.sanitize();
            return state;
        } catch (IOException | RuntimeException malformed) {
            return new UiState();
        }
    }

    public void save(UiState state) throws IOException {
        state.sanitize();
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
