package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.database.DatabaseSettings;
class ShopAdminStoreTest {
 @TempDir Path dir;ShopStore store;String id;byte[] original={10,20,30};
 @BeforeEach void setup()throws Exception{store=new ShopStore(DatabaseSettings.load(dir.resolve("none.properties")),dir.resolve("isolated.db"));store.create("a","상점");id=store.add("a","원본",original,"10","5");}
 @AfterEach void close()throws Exception{store.close();}
 long revision()throws Exception{return store.catalog().get("a").revision();}
 ShopStore.Product product()throws Exception{return store.catalog().get("a").products().getFirst();}
 @Test void addRetainsFullPayloadAndNormalizesUnit()throws Exception{byte[] bytes=new byte[32768];Arrays.fill(bytes,(byte)7);String added=store.adminAdd("a",revision(),"커스텀",bytes,"","0");assertArrayEquals(bytes,store.catalog().get("a").products().stream().filter(p->p.id().equals(added)).findFirst().orElseThrow().bytes());}
 @Test void editPreservesOriginalItemAndSupportsBothDirectionsDisabled()throws Exception{store.adminEdit("a",revision(),id,"변경 이름","","");assertArrayEquals(original,product().bytes());assertEquals("변경 이름",product().name());assertEquals("",product().buy());assertEquals("",product().sell());}
 @Test void secondConnectionCannotSaveOrDeleteStaleProduct()throws Exception{long stale=revision();try(var other=new ShopStore(DatabaseSettings.load(dir.resolve("none.properties")),dir.resolve("isolated.db"))){other.adminEdit("a",stale,id,"다른 관리자","20","0");assertThrows(IllegalStateException.class,()->store.adminEdit("a",stale,id,"덮어쓰기","1","1"));assertThrows(IllegalStateException.class,()->store.adminDelete("a",stale,id));assertThrows(IllegalStateException.class,()->store.adminAdd("a",stale,"새상품",original,"1","1"));}assertEquals("다른 관리자",product().name());}
 @Test void deleteDoesNotRemovePaidOrdersOrOriginalDeliveryPayload()throws Exception{UUID owner=UUID.randomUUID();var order=store.prepare(owner,UUID.randomUUID().toString(),"a",revision(),id,1,false);store.state(order.id(),"prepared","paid");store.adminDelete("a",revision(),id);assertTrue(store.catalog().get("a").products().isEmpty());assertArrayEquals(original,store.paid(owner).getFirst().bytes());}
 @Test void wrongShopCannotEditOrDeleteAndTransactionRollsBack()throws Exception{store.create("b","다른 상점");long rev=store.catalog().get("b").revision(),version=store.version();assertThrows(Exception.class,()->store.adminEdit("b",rev,id,"오류","1","1"));assertThrows(Exception.class,()->store.adminDelete("b",rev,id));assertEquals(version,store.version());assertEquals(rev,store.catalog().get("b").revision());assertEquals("원본",product().name());}
 @Test void invalidNamesPayloadAndPricesCannotEnterStore()throws Exception{long rev=revision();assertThrows(Exception.class,()->store.adminAdd("a",rev,"",original,"1","1"));assertThrows(Exception.class,()->store.adminAdd("a",rev,"이름",new byte[32769],"1","1"));assertThrows(Exception.class,()->store.adminAdd("a",rev,"이름",original,"-1","1"));assertThrows(Exception.class,()->store.adminEdit("a",rev,id,"줄\n바꿈","1","1"));assertEquals(rev,revision());}
 @Test void categoryAndSortOrderTheCatalogueAndEditKeepsItem()throws Exception{
  store.adminAdd("a",revision(),"나중",new byte[]{1},"1","","광물",20);store.adminAdd("a",revision(),"먼저",new byte[]{2},"1","","광물",10);
  var names=store.catalog().get("a").products().stream().map(ShopStore.Product::name).toList();assertEquals(List.of("원본","먼저","나중"),names);
  var first=store.catalog().get("a").products().get(1);store.adminEdit("a",revision(),first.id(),"먼저","2","1","나무",30);
  var edited=store.catalog().get("a").products().stream().filter(p->p.id().equals(first.id())).findFirst().orElseThrow();assertEquals("나무",edited.category());assertEquals(30,edited.sort());assertArrayEquals(new byte[]{2},edited.bytes());
  assertThrows(IllegalArgumentException.class,()->store.adminAdd("a",revision(),"x",new byte[]{1},"1","","광물",100000));assertThrows(IllegalArgumentException.class,()->store.adminAdd("a",revision(),"x",new byte[]{1},"1","","a".repeat(41),0));}
 @Test void importCreatesShopUpdatesSameNameAndIsAllOrNothing()throws Exception{
  var rows=List.of(new ShopStore.ImportEntry("석탄",new byte[]{5},"100","20","광물",10),new ShopStore.ImportEntry("다이아몬드",new byte[]{6},"2000","","광물",20));
  var first=store.importProducts("pipi","피피의 잡화점","",rows);assertTrue(first.created());assertEquals(2,first.added());
  var again=store.importProducts("pipi","피피의 잡화점","pipi-neutral",List.of(new ShopStore.ImportEntry("석탄",new byte[]{9},"120","25","광물",15)));assertFalse(again.created());assertEquals(1,again.updated());
  var shop=store.catalog().get("pipi");assertEquals("pipi-neutral",shop.portrait());var coal=shop.products().getFirst();assertEquals("석탄",coal.name());assertEquals("120",coal.buy());assertEquals(15,coal.sort());assertArrayEquals(new byte[]{5},coal.bytes());assertEquals(2,shop.products().size());
  assertThrows(IllegalArgumentException.class,()->store.importProducts("pipi","피피의 잡화점","",List.of(new ShopStore.ImportEntry("새것",new byte[]{1},"1","","",0),new ShopStore.ImportEntry("새것",new byte[]{1},"1","","",0))));assertEquals(2,store.catalog().get("pipi").products().size());}
}
