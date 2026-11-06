package kr.kkaegul.client.casino.seotda;

/** 섯다 화면용 한 줄 입력칸 (글자 / 숫자) */
public final class SdField {

    private final StringBuilder sb = new StringBuilder();
    public final int max;
    public final boolean digits;

    public SdField(int max, boolean digits) {
        this.max = max;
        this.digits = digits;
    }

    public String get() {
        return sb.toString();
    }

    public void set(String s) {
        sb.setLength(0);
        type(s);
    }

    public void type(String s) {
        if (s == null) return;
        for (char ch : s.toCharArray()) {
            if (sb.length() >= max) return;
            if (ch < 32 || ch == 127 || ch == '§') continue;
            if (digits && (ch < '0' || ch > '9')) continue;
            if (digits && sb.length() == 0 && ch == '0') continue;
            sb.append(ch);
        }
    }

    public void backspace() {
        if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
    }

    public long number() {
        try {
            return sb.isEmpty() ? 0 : Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    public int length() {
        return sb.codePointCount(0, sb.length());
    }
}
