package kr.chacademy.portrait.core;
public final class InventoryReceipt {
    public static final String DEBITED="D", REFUNDED="R";
    public interface Account {
        String marker(); void marker(String value); Object snapshot(); void restore(Object before);
        boolean take(); boolean give(); void save();
    }
    private InventoryReceipt(){}
    public static boolean debit(Account a) {
        if(a.marker()!=null)return false;
        Object before=a.snapshot();
        try { if(!a.take())return false; a.marker(DEBITED); a.save(); return true; }
        catch(RuntimeException e){a.restore(before);throw e;}
    }
    public static boolean refund(Account a) {
        if(REFUNDED.equals(a.marker()))return true;
        if(!DEBITED.equals(a.marker()))return false;
        Object before=a.snapshot();
        try {if(!a.give())return false;a.marker(REFUNDED);a.save();return true;}
        catch(RuntimeException e){a.restore(before);throw e;}
    }
}
