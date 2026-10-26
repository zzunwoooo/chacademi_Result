package kr.chacademi.chatlayout;
import java.util.*;
/** Pure transfer plan: a tab object belongs to exactly one resulting list. */
public final class TabTransfer {
    private TabTransfer(){}
    public record Plan<T>(List<T> source,List<T> destination,int sourceSelection,int destinationSelection){}
    public static <T> Plan<T> plan(List<T> source,List<T> destination,T moved,T sourceSelected,T destinationSelected,int insertion) {
        Objects.requireNonNull(source);Objects.requireNonNull(destination);Objects.requireNonNull(moved);
        if(source==destination)throw new IllegalArgumentException("Use same-window reorder");
        int from=identityIndex(source,moved);
        if(from<0||identityIndex(destination,moved)>=0)throw new IllegalArgumentException("Tab must belong to source only");
        if(insertion<0||insertion>destination.size())throw new IndexOutOfBoundsException(insertion);
        var a=new ArrayList<>(source);var b=new ArrayList<>(destination);
        a.remove(from);b.add(insertion,moved);
        int selectedA=identityIndex(a,sourceSelected);
        if(selectedA<0)selectedA=a.isEmpty()?-1:Math.min(from,a.size()-1);
        int selectedB=identityIndex(b,destinationSelected);
        if(selectedB<0)selectedB=insertion;
        return new Plan<>(a,b,selectedA,selectedB);
    }
    public static <T> int identityIndex(List<T> list,T value) {
        for(int i=0;i<list.size();i++)if(list.get(i)==value)return i;return -1;
    }
}
