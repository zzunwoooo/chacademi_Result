package kr.chacademi.chatlayout;
import java.util.*;
import java.util.function.*;
/** Pure category operations shared by delivery and close, independent of native rendering. */
public final class PrivateCategoryPolicy {
 private PrivateCategoryPolicy(){}
 public static <W,T> W host(List<W> windows,Function<W,List<T>> tabs,Predicate<T> privateTab){
  W best=windows.getFirst();long most=-1;
  for(W window:windows){long count=tabs.apply(window).stream().filter(privateTab.negate()).count();
   if(count>most){most=count;best=window;}}
  return best;
 }
 public static <T> List<T> without(List<T> tabs,Predicate<T> removed){
  return tabs.stream().filter(removed.negate()).toList();
 }
}
