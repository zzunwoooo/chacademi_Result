package school.magiccodex.client;

/** Edge-triggered raw keyboard shortcut; independent of Minecraft's one-binding-per-key lookup. */
public final class CodexShortcut {
    public enum Result { PASS, OPEN, CONSUME }
    private int capturedKey = -1;

    /** GLFW actions: release=0, press=1, repeat=2. Repeats remain consumed after the screen opens. */
    public Result handle(int key, int action, boolean matchesBinding, boolean gameplayAvailable) {
        if (capturedKey >= 0 && key == capturedKey) {
            if (action == 0) { capturedKey = -1; return Result.PASS; }
            return Result.CONSUME;
        }
        if (key < 0 || action != 1 || !matchesBinding || !gameplayAvailable) return Result.PASS;
        capturedKey = key;
        return Result.OPEN;
    }
    public void reset() { capturedKey = -1; }
}
