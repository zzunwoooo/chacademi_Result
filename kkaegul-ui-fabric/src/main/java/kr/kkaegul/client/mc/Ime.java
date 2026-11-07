package kr.kkaegul.client.mc;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 윈도우 한글 입력기(IME) 끄기/켜기. 입력칸이 있는 창이 열려 있는 동안 IME 를 꺼서
 * 윈도우 조합창이 안 뜨게 하고, 한글은 게임 안 입력기(HangulInput)가 직접 조합한다.
 * 윈도우가 아니거나 JNA 가 없으면 아무것도 안 함.
 */
public final class Ime {
    private static final Logger LOG = LoggerFactory.getLogger("kkaegul-ui");
    private static boolean off;

    private Ime() {}

    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    public static void disable() { set(false); }

    public static void restore() { set(true); }

    private static void set(boolean on) {
        if (!windows() || off == !on) return;
        try {
            long window = MinecraftClient.getInstance().getWindow().getHandle();
            long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
            if (hwnd == 0) return;
            com.sun.jna.Function f = com.sun.jna.NativeLibrary.getInstance("imm32").getFunction("ImmAssociateContextEx");
            // on: IACE_DEFAULT(0x10) 기본 입력기 다시 연결 / off: 입력기 떼기 (hIMC = NULL, flags = 0)
            f.invokeInt(new Object[]{new com.sun.jna.Pointer(hwnd), null, on ? 0x10 : 0});
            off = !on;
        } catch (Throwable t) {
            LOG.debug("IME 전환 실패", t);
        }
    }
}
