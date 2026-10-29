package kr.chacademi.dungeon;

/**
 * MagicCodex HUD 위치 편집(HudCustomization)에 리플렉션으로 붙는다 (스토리 모드 HudEditing 과 같은 방식, id 만 다름).
 * 붙으면 사용자가 Ctrl 포인터/채팅에서 Shift+드래그로 옮기고 Shift+휠로 크기를 바꿀 수 있고, 위치는 hud-layouts.properties 에 저장된다.
 * MagicCodex 가 없으면 아무 일도 하지 않는다.
 */
final class HudLink {
    private HudLink() {}
    interface Scope extends AutoCloseable { void close(); }
    private static final String TYPE = "school.magiccodex.client.HudCustomization";

    static Scope begin(Object graphics, String id, float x, float y, float w, float h) {
        try {
            var scope = (AutoCloseable) Class.forName(TYPE).getMethod("beginExternal", Object.class, String.class, float.class, float.class, float.class, float.class)
                    .invoke(null, graphics, id, x, y, w, h);
            return () -> { try { scope.close(); } catch (Exception ignored) { } };
        } catch (ReflectiveOperationException | LinkageError e) { return () -> { }; }
    }

    /** 화면 좌표 → 이 HUD 의 원래(옮기기 전) 좌표. */
    static double[] pointer(String id, double x, double y) {
        try { return (double[]) Class.forName(TYPE).getMethod("pointerExternal", String.class, double.class, double.class).invoke(null, id, x, y); }
        catch (ReflectiveOperationException | LinkageError e) { return new double[]{x, y}; }
    }

    /** 방금(0.3초 안에) 그려진 HUD 상자 {x,y,w,h} — 실제 화면 위치(사용자가 옮긴 곳·크기 반영). 없으면 null. */
    static float[] box(String id) {
        try { return (float[]) Class.forName(TYPE).getMethod("recentBoxExternal", String.class).invoke(null, id); }
        catch (ReflectiveOperationException | LinkageError | ClassCastException e) { return null; }
    }

    static boolean editing() {
        try { return (Boolean) Class.forName(TYPE).getMethod("editingExternal").invoke(null); }
        catch (ReflectiveOperationException | LinkageError e) { return false; }
    }

    static boolean overlaps(float[] a, float x, float y, float w, float h) {
        return a != null && a[0] < x + w && a[0] + a[2] > x && a[1] < y + h && a[1] + a[3] > y;
    }
}
