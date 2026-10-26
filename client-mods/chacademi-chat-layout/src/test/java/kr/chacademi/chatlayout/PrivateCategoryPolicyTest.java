package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PrivateCategoryPolicyTest {
 record Tab(String name,boolean peer){}
 static List<Tab> tabs(Tab... t){return new ArrayList<>(List.of(t));}
 static Tab general=new Tab("general",false),party=new Tab("party",false),system=new Tab("system",false);
 static Tab alice=new Tab("alice",true),bob=new Tab("bob",true);
 @Test void incomingUsesCategoryHostEvenWhenDetachedWindowIsFront(){
  var host=tabs(general,party);var detached=tabs(alice);var windows=List.of(detached,tabs(system),host);
  assertSame(host,PrivateCategoryPolicy.host(windows,w->w,Tab::peer));assertEquals(3,windows.size());
 }
 @Test void stableHostAfterFrontOrderChanges(){
  var host=tabs(general,party);var detached=tabs(alice);
  assertSame(host,PrivateCategoryPolicy.host(List.of(host,detached),w->w,Tab::peer));
  assertSame(host,PrivateCategoryPolicy.host(List.of(detached,host),w->w,Tab::peer));
 }
 @Test void allPrivateFallbackDoesNotCreateWindow(){
  var host=tabs(alice);assertSame(host,PrivateCategoryPolicy.host(List.of(host,tabs(bob)),w->w,Tab::peer));
 }
 @Test void closingPeerKeepsOtherCategoriesAndTheirIdentity(){
  var result=PrivateCategoryPolicy.without(tabs(general,alice,party,bob),t->t.name().equals("alice"));
  assertEquals(List.of(general,party,bob),result);assertSame(party,result.get(1));
 }
 @Test void detachedPeerCloseLeavesEmptyWindowForRemoval(){
  assertTrue(PrivateCategoryPolicy.without(tabs(alice),t->t==alice).isEmpty());
 }
 @Test void removesDuplicatePeerCategoriesWithoutRestoringAny(){
  assertEquals(List.of(party),PrivateCategoryPolicy.without(tabs(alice,party,alice),t->t==alice));
 }
 @Test void categoryRemovalDoesNotMutateOriginalHistoryOwner(){
  var original=tabs(alice,bob);PrivateCategoryPolicy.without(original,t->t==alice);
  assertEquals(List.of(alice,bob),original);
 }
 @Test void redeliveryAfterRemovalUsesOriginalCategoryHost(){
  var host=tabs(general,party);var windows=new ArrayList<>(List.of(host,tabs(alice)));
  windows.removeIf(w->PrivateCategoryPolicy.without(w,t->t==alice).isEmpty());
  var target=PrivateCategoryPolicy.host(windows,w->w,Tab::peer);target.add(alice);
  assertSame(host,target);assertEquals(1,windows.size());assertEquals(1,host.stream().filter(t->t==alice).count());
 }
}
