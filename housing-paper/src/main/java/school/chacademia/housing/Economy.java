package school.chacademia.housing;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Vault 경제 (리플렉션, MagicCodexBridge 상점과 같은 방식). 메인 스레드에서만 부른다. */
final class Economy {
    private record Provider(Class<?> type, Object provider) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Provider provider() {
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices())
            if (type.getName().equals("net.milkbowl.vault.economy.Economy")) {
                var r = Bukkit.getServicesManager().getRegistration((Class) type);
                if (r != null) return new Provider(type, r.getProvider());
            }
        return null;
    }

    static boolean available() { return provider() != null; }

    static double balance(OfflinePlayer p) {
        var e = provider();
        if (e == null) return -1;
        try { double v = ((Number) e.type().getMethod("getBalance", OfflinePlayer.class).invoke(e.provider(), p)).doubleValue(); return Double.isFinite(v) ? v : -1; }
        catch (ReflectiveOperationException | RuntimeException err) { return -1; }
    }

    /** @return true = 빠짐 (잔액 부족·경제 없음·실패면 false) */
    static boolean withdraw(OfflinePlayer p, long amount) {
        if (amount <= 0) return true;
        var e = provider();
        if (e == null || balance(p) < amount) return false;
        return call(e, "withdrawPlayer", p, amount);
    }

    static boolean deposit(OfflinePlayer p, long amount) {
        if (amount <= 0) return true;
        var e = provider();
        return e != null && call(e, "depositPlayer", p, amount);
    }

    private static boolean call(Provider e, String method, OfflinePlayer p, long amount) { return pay(e, method, p, amount) == Pay.OK; }

    /** 돈 이동 결과 (A03, 2026-10-21): 거절(돈 안 움직임)과 결과 모름(예외)을 구분한다. */
    enum Pay { OK, REFUSED, UNCERTAIN }

    /** 출금. 경제 없음·잔액 부족·거래 거절 = REFUSED (돈 안 빠짐), 호출 중 예외 = UNCERTAIN (빠졌을 수 있음). */
    static Pay withdrawResult(OfflinePlayer p, long amount) {
        if (amount <= 0) return Pay.OK;
        var e = provider();
        if (e == null) return Pay.REFUSED;
        double balance = balance(p);
        if (balance >= 0 && balance < amount) return Pay.REFUSED;
        return pay(e, "withdrawPlayer", p, amount);
    }

    /** 입금. 경제 없음·거래 거절 = REFUSED, 호출 중 예외 = UNCERTAIN (들어갔을 수 있음). */
    static Pay depositResult(OfflinePlayer p, long amount) {
        if (amount <= 0) return Pay.OK;
        var e = provider();
        return e == null ? Pay.REFUSED : pay(e, "depositPlayer", p, amount);
    }

    private static Pay pay(Provider e, String method, OfflinePlayer p, long amount) {
        Object result;
        try { result = e.type().getMethod(method, OfflinePlayer.class, double.class).invoke(e.provider(), p, (double) amount); }
        catch (NoSuchMethodException | IllegalAccessException err) { return Pay.REFUSED; }          // 호출 자체가 안 됨: 돈은 안 움직임
        catch (ReflectiveOperationException | RuntimeException err) { return Pay.UNCERTAIN; }       // 경제 플러그인 안에서 예외: 결과 모름
        try { return (boolean) result.getClass().getMethod("transactionSuccess").invoke(result) ? Pay.OK : Pay.REFUSED; }
        catch (ReflectiveOperationException | RuntimeException err) { return Pay.UNCERTAIN; }
    }

    private Economy() {}
}
