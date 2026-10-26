package kr.chacademi.chatlayout;

/** Pointer movement in viewport fractions remains valid when GUI scale changes mid-drag. */
public record DragSession(double startAnchorX, double startAnchorY, double startMouseX, double startMouseY) {
    public record Anchor(double x, double y) {}
    public static DragSession begin(double anchorX, double anchorY, double mx, double my, int width, int height) {
        return new DragSession(anchorX, anchorY, mx / width, my / height);
    }
    public Anchor move(double mx, double my, int width, int height) {
        return new Anchor(startAnchorX + mx / width - startMouseX, startAnchorY + my / height - startMouseY);
    }
}
