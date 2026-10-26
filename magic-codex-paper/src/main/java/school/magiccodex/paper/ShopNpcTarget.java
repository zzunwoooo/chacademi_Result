package school.magiccodex.paper;
/** Distinct target kind in the existing server-scoped NPC binding table; never a normal shop ID. */
final class ShopNpcTarget {
 static final String FISH="@fish_shop",LABEL="물고기판매소";
 static String canonical(String id){return LABEL.equals(id)?FISH:id;}
 static boolean fish(String id){return FISH.equals(canonical(id));}
 static boolean validKey(String key){return key!=null&&key.matches("citizens:[0-9]{1,9}|tag:[a-zA-Z0-9_-]{1,64}");}
 static String label(String id){return fish(id)?"[물고기 판매소]":"[일반 상점] "+id;}
}
