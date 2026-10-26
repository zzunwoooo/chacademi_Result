package school.magiccodex.paper;
import java.math.BigDecimal;import java.nio.file.Path;import java.sql.*;import java.util.*;
import school.magiccodex.database.*;
final class ShopStore implements AutoCloseable {
 /** category·sort: 상점 화면 정렬용. 목록은 순서(sort) → 이름 순. */
 /** costItem·costCount: 교환 상품(2026-10-25). costCount&gt;0 이면 돈 대신 그 아이템을 내고 사며, 판매는 막힌다(buy·sell 값은 남겨 두되 쓰지 않음). */
 record Product(String id,String name,byte[] bytes,String buy,String sell,String category,int sort,String costItem,int costCount){
  Product(String id,String name,byte[] bytes,String buy,String sell,String category,int sort){this(id,name,bytes,buy,sell,category,sort,"",0);}
  Product(String id,String name,byte[] bytes,String buy,String sell){this(id,name,bytes,buy,sell,"",0);}
  boolean exchange(){return costCount>0;}
 }
 /** 파일 일괄 등록 한 줄. bytes = 1개짜리 아이템 원본. */
 record ImportEntry(String name,byte[] bytes,String buy,String sell,String category,int sort,String costItem,int costCount){
  ImportEntry(String name,byte[] bytes,String buy,String sell,String category,int sort){this(name,bytes,buy,sell,category,sort,"",0);}
 }
 record ImportResult(boolean created,int added,int updated){}
 /** owner·role: 상점 창 이름표(NPC 이름이 없을 때의 주인 이름, 이름 위 역할 문구). 빈 값이면 기본값. */
 record Shop(String id,String title,String portrait,long revision,List<Product> products,String owner,String role){
  Shop(String id,String title,String portrait,long revision,List<Product> products){this(id,title,portrait,revision,products,"","");}
 }
 /** origin: 거래를 시작한 서버(mailbox-origin.txt). 빈 값은 이 열이 생기기 전의 기록이며 자동 복구 대상에서 제외한다.
  * mode "exchange"(교환 상품 구매): total = 낼 비용 아이템 개수, cost = 그 아이템 키. 그 밖의 거래는 cost 가 비어 있다. */
 record Order(String id,UUID owner,String state,String mode,String product,int quantity,String total,byte[] bytes,String title,String origin,long created,String cost){
  Order(String id,UUID owner,String state,String mode,String product,int quantity,String total,byte[] bytes,String title,String origin,long created){this(id,owner,state,mode,product,quantity,total,bytes,title,origin,created,"");}
  boolean exchange(){return mode.equals("exchange");}
 }
 private final ConnectionHolder holder;private final String shops,products,orders,bindings,version,tutorial;
 ShopStore(DatabaseSettings settings,Path path)throws Exception{holder=settings.holder(path);shops=settings.table("shops","shops");products=settings.table("shop_products","shop_products");orders=settings.table("shop_orders","shop_orders");bindings=settings.table("shop_bindings","shop_bindings");version=settings.table("shop_version","shop_version");tutorial=settings.table("shop_tutorial","shop_tutorial");String tail=settings.mariaDb()?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";try(var s=db().createStatement()){
 s.execute("CREATE TABLE IF NOT EXISTS "+shops+"(id VARCHAR(48) PRIMARY KEY,title VARCHAR(80) NOT NULL,portrait VARCHAR(120) NOT NULL,revision BIGINT NOT NULL)"+tail);
 s.execute("CREATE TABLE IF NOT EXISTS "+products+"(id VARCHAR(36) PRIMARY KEY,shop VARCHAR(48) NOT NULL,name VARCHAR(120) NOT NULL,payload BLOB NOT NULL,buy VARCHAR(32) NOT NULL,sell VARCHAR(32) NOT NULL)"+tail);
 s.execute("CREATE TABLE IF NOT EXISTS "+orders+"(id VARCHAR(36) PRIMARY KEY,owner VARCHAR(36) NOT NULL,request_key VARCHAR(36) NOT NULL UNIQUE,state VARCHAR(20) NOT NULL,mode VARCHAR(8) NOT NULL,product VARCHAR(36) NOT NULL,quantity INTEGER NOT NULL,total VARCHAR(32) NOT NULL,payload BLOB NOT NULL,title VARCHAR(80) NOT NULL,created BIGINT NOT NULL)"+tail);
 s.execute("CREATE TABLE IF NOT EXISTS "+bindings+"(origin VARCHAR(36) NOT NULL,npc VARCHAR(80) NOT NULL,shop VARCHAR(48) NOT NULL,PRIMARY KEY(origin,npc))"+tail);
 s.execute("CREATE TABLE IF NOT EXISTS "+version+"(id INTEGER PRIMARY KEY,revision BIGINT NOT NULL)"+tail);
 // 튜토리얼 전용 재료 상점 대상 유저 (2026-10-25, 메모 #12). started = 구매를 세기 시작한 시각.
 s.execute("CREATE TABLE IF NOT EXISTS "+tutorial+"(owner VARCHAR(36) PRIMARY KEY,started BIGINT NOT NULL)"+tail);
 }
 // 추가 전용 스키마 변경: 이미 있으면 건너뛴다 (SQLite·MariaDB 공통).
 boolean hasOrigin=true;try(var s=db().createStatement();var r=s.executeQuery("SELECT origin FROM "+orders+" WHERE 1=0")){r.next();}catch(SQLException e){hasOrigin=false;}
 if(!hasOrigin)try(var s=db().createStatement()){s.execute("ALTER TABLE "+orders+" ADD COLUMN origin VARCHAR(36) NOT NULL DEFAULT ''");}catch(SQLException e){try(var s=db().createStatement();var r=s.executeQuery("SELECT origin FROM "+orders+" WHERE 1=0")){r.next();}catch(SQLException again){throw e;}}
 for(String[] tc:List.of(new String[]{products,"category VARCHAR(40) NOT NULL DEFAULT ''"},new String[]{products,"sort_order INTEGER NOT NULL DEFAULT 0"},new String[]{shops,"owner VARCHAR(40) NOT NULL DEFAULT ''"},new String[]{shops,"role VARCHAR(40) NOT NULL DEFAULT ''"},new String[]{products,"cost_item VARCHAR(80) NOT NULL DEFAULT ''"},new String[]{products,"cost_count INTEGER NOT NULL DEFAULT 0"},new String[]{orders,"cost VARCHAR(80) NOT NULL DEFAULT ''"})){String table=tc[0],col=tc[1],name=col.substring(0,col.indexOf(' '));boolean has=true;try(var s=db().createStatement();var r=s.executeQuery("SELECT "+name+" FROM "+table+" WHERE 1=0")){r.next();}catch(SQLException e){has=false;}
  if(!has)try(var s=db().createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+col);}catch(SQLException e){try(var s=db().createStatement();var r=s.executeQuery("SELECT "+name+" FROM "+table+" WHERE 1=0")){r.next();}catch(SQLException again){throw e;}}}
 for(String sql:List.of("CREATE INDEX IF NOT EXISTS "+orders+"_owner_state ON "+orders+"(owner,state)","CREATE INDEX IF NOT EXISTS "+products+"_shop ON "+products+"(shop)"))try(var s=db().createStatement()){s.execute(sql);}catch(SQLException ignored){/* 색인은 성능용이라 만들지 못해도 동작한다 */}
 try(var s=db().prepareStatement("INSERT INTO "+version+"(id,revision) VALUES(1,0)")){try{s.executeUpdate();}catch(SQLException e){if(e.getErrorCode()!=19&&e.getErrorCode()!=1062)throw e;}}}
 private Connection db()throws SQLException{return holder.get();}private interface Work<T>{T run()throws Exception;}
 /** 카탈로그(상점·상품·NPC 연결)를 바꾸는 쓰기 전용: 공용 버전을 올려 두 서버가 카탈로그를 다시 읽게 한다. */
 private <T>T tx(Work<T> work)throws Exception{return tx(true,work);}
 /** bump=false(거래 기록): 같은 버전 행을 잠그기만 하고 값은 바꾸지 않는다. 서버 간 직렬화는 유지하되 거래마다 카탈로그를 다시 읽지 않는다. */
 private <T>T tx(boolean bump,Work<T> work)throws Exception{var c=holder.begin();try{try(var s=c.createStatement()){s.executeUpdate("UPDATE "+version+" SET revision=revision"+(bump?"+1":"")+" WHERE id=1");}T out=work.run();c.commit();return out;}catch(Exception e){holder.rollback();throw e;}finally{holder.end();}}
 static String price(String value){if(value.equalsIgnoreCase("off"))return "";var p=new BigDecimal(value);if(p.signum()<0||p.compareTo(new BigDecimal("1000000000000"))>0||p.scale()>6)throw new IllegalArgumentException("가격은 0 이상, 소수점 6자리 이하로 입력하세요.");return p.stripTrailingZeros().toPlainString();}
 long version()throws SQLException{try(var s=db().createStatement();var r=s.executeQuery("SELECT revision FROM "+version+" WHERE id=1")){r.next();return r.getLong(1);}}
 Map<String,Shop> catalog()throws SQLException{var out=new HashMap<String,Shop>();try(var s=db().createStatement();var r=s.executeQuery("SELECT id,title,portrait,revision,owner,role FROM "+shops+" ORDER BY id")){while(r.next()){String id=r.getString(1);var list=new ArrayList<Product>();try(var p=db().prepareStatement("SELECT id,name,payload,buy,sell,category,sort_order,cost_item,cost_count FROM "+products+" WHERE shop=? ORDER BY sort_order,name,id")){p.setString(1,id);try(var q=p.executeQuery()){while(q.next())list.add(new Product(q.getString(1),q.getString(2),q.getBytes(3),q.getString(4),q.getString(5),q.getString(6),q.getInt(7),q.getString(8),q.getInt(9)));}}out.put(id,new Shop(id,r.getString(2),r.getString(3),r.getLong(4),List.copyOf(list),r.getString(5),r.getString(6)));}}return Map.copyOf(out);}
 /** 탭 완성용: 상품 payload 를 읽지 않고 ID만. */
 List<String> shopIds()throws SQLException{var out=new ArrayList<String>();try(var s=db().createStatement();var r=s.executeQuery("SELECT id FROM "+shops+" ORDER BY id")){while(r.next())out.add(r.getString(1));}return List.copyOf(out);}
 Map<String,String> bindings(String origin)throws SQLException{var out=new HashMap<String,String>();try(var s=db().prepareStatement("SELECT npc,shop FROM "+bindings+" WHERE origin=?")){s.setString(1,origin);try(var r=s.executeQuery()){while(r.next())out.put(r.getString(1),r.getString(2));}}return Map.copyOf(out);}
 void create(String id,String title)throws Exception{if(!id.matches("[a-z0-9_-]{1,48}")||title.isBlank()||title.length()>80)throw new IllegalArgumentException("상점 ID/이름을 확인하세요.");tx(()->{try(var s=db().prepareStatement("INSERT INTO "+shops+"(id,title,portrait,revision) VALUES(?,?,'elena-neutral',1)")){s.setString(1,id);s.setString(2,title);s.executeUpdate();}return null;});}
 private void bump(String shop)throws SQLException{try(var s=db().prepareStatement("UPDATE "+shops+" SET revision=revision+1 WHERE id=?")){s.setString(1,shop);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상점 없음");}}
 String add(String shop,String name,byte[] payload,String buy,String sell)throws Exception{return tx(()->{bump(shop);String id=UUID.randomUUID().toString();try(var s=db().prepareStatement("INSERT INTO "+products+"(id,shop,name,payload,buy,sell) VALUES(?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,shop);s.setString(3,name);s.setBytes(4,payload);s.setString(5,buy);s.setString(6,sell);s.executeUpdate();}return id;});}
 void prices(String shop,String id,String buy,String sell)throws Exception{tx(()->{bump(shop);try(var s=db().prepareStatement("UPDATE "+products+" SET buy=?,sell=? WHERE shop=? AND id=?")){s.setString(1,buy);s.setString(2,sell);s.setString(3,shop);s.setString(4,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상품 없음");}return null;});}
 /** 교환 비용 지정(costItem 키, costCount 1~2304) 또는 해제(""·0). 구매가·판매가 값은 그대로 두어 해제하면 원래 가격으로 돌아간다. */
 void exchange(String shop,String id,String costItem,int costCount)throws Exception{ShopExchange.validate(costItem,costCount);tx(()->{bump(shop);try(var s=db().prepareStatement("UPDATE "+products+" SET cost_item=?,cost_count=? WHERE shop=? AND id=?")){s.setString(1,costItem);s.setInt(2,costCount);s.setString(3,shop);s.setString(4,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상품 없음");}return null;});}
 void portrait(String shop,String portrait)throws Exception{if(!portrait.matches("[a-z0-9_/-]{1,120}")||portrait.contains(".."))throw new IllegalArgumentException("초상 ID");tx(()->{bump(shop);try(var s=db().prepareStatement("UPDATE "+shops+" SET portrait=? WHERE id=?")){s.setString(1,portrait);s.setString(2,shop);s.executeUpdate();}return null;});}
 void bind(String origin,String npc,String input)throws Exception{String shop=ShopNpcTarget.canonical(input);if(!ShopNpcTarget.validKey(npc))throw new IllegalArgumentException("NPC 연결키");tx(()->{if(!ShopNpcTarget.fish(shop))bump(shop);try(var s=db().prepareStatement("DELETE FROM "+bindings+" WHERE origin=? AND npc=?")){s.setString(1,origin);s.setString(2,npc);s.executeUpdate();}try(var s=db().prepareStatement("INSERT INTO "+bindings+"(origin,npc,shop) VALUES(?,?,?)")){s.setString(1,origin);s.setString(2,npc);s.setString(3,shop);s.executeUpdate();}return null;});}

 boolean unbind(String origin,String npc,String expected)throws Exception{
  if(!ShopNpcTarget.validKey(npc))throw new IllegalArgumentException("NPC 연결키");String target=ShopNpcTarget.canonical(expected);
  return tx(()->{try(var p=db().prepareStatement("DELETE FROM "+bindings+" WHERE origin=? AND npc=? AND shop=?")){p.setString(1,origin);p.setString(2,npc);p.setString(3,target);return p.executeUpdate()==1;}});
 }

 /** The shared version row is locked by tx before this comparison. */
 private void adminRevision(String shop,long expected)throws SQLException{try(var s=db().prepareStatement("SELECT revision FROM "+shops+" WHERE id=?")){s.setString(1,shop);try(var q=s.executeQuery()){if(!q.next())throw new IllegalArgumentException("상점 없음");if(q.getLong(1)!=expected)throw new IllegalStateException("다른 관리자가 상품을 변경했습니다. 새로고침 후 다시 확인하세요.");}}}
 private static void sortValues(String category,int sort){if(category.length()>40||category.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("분류는 40자 이하로 입력하세요.");if(sort<0||sort>99999)throw new IllegalArgumentException("순서는 0~99999 사이 숫자예요.");}
 private static void adminValues(String name,byte[] payload,String buy,String sell){if(name.isBlank()||name.length()>120||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("상품 이름 확인");if(payload==null||payload.length<1||payload.length>32768)throw new IllegalArgumentException("아이템 데이터 크기 확인");for(String v:List.of(buy,sell))if(!v.isEmpty()&&!price(v).equals(v))throw new IllegalArgumentException("가격 확인");}
 String adminAdd(String shop,long expected,String name,byte[] payload,String buy,String sell)throws Exception{return adminAdd(shop,expected,name,payload,buy,sell,"",0);}
 String adminAdd(String shop,long expected,String name,byte[] payload,String buy,String sell,String category,int sort)throws Exception{adminValues(name,payload,buy,sell);sortValues(category,sort);return tx(()->{adminRevision(shop,expected);bump(shop);return insert(shop,name,payload,buy,sell,category.strip(),sort);});}
 private String insert(String shop,String name,byte[] payload,String buy,String sell,String category,int sort)throws SQLException{String id=UUID.randomUUID().toString();try(var s=db().prepareStatement("INSERT INTO "+products+"(id,shop,name,payload,buy,sell,category,sort_order) VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,shop);s.setString(3,name);s.setBytes(4,payload);s.setString(5,buy);s.setString(6,sell);s.setString(7,category);s.setInt(8,sort);s.executeUpdate();}return id;}
 void adminEdit(String shop,long expected,String id,String name,String buy,String sell)throws Exception{adminValues(name,new byte[]{1},buy,sell);tx(()->{adminRevision(shop,expected);try(var s=db().prepareStatement("UPDATE "+products+" SET name=?,buy=?,sell=? WHERE shop=? AND id=?")){s.setString(1,name);s.setString(2,buy);s.setString(3,sell);s.setString(4,shop);s.setString(5,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상품 없음");}bump(shop);return null;});}
 void adminEdit(String shop,long expected,String id,String name,String buy,String sell,String category,int sort)throws Exception{adminValues(name,new byte[]{1},buy,sell);sortValues(category,sort);tx(()->{adminRevision(shop,expected);try(var s=db().prepareStatement("UPDATE "+products+" SET name=?,buy=?,sell=?,category=?,sort_order=? WHERE shop=? AND id=?")){s.setString(1,name);s.setString(2,buy);s.setString(3,sell);s.setString(4,category.strip());s.setInt(5,sort);s.setString(6,shop);s.setString(7,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상품 없음");}bump(shop);return null;});}
 void adminDelete(String shop,long expected,String id)throws Exception{tx(()->{adminRevision(shop,expected);try(var s=db().prepareStatement("DELETE FROM "+products+" WHERE shop=? AND id=?")){s.setString(1,shop);s.setString(2,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("상품 없음");}bump(shop);return null;});}
 /**
  * 파일 일괄 등록: 상점이 없으면 만들고, 같은 이름의 상품은 가격·분류·순서만 갱신(아이템 원본은 그대로), 없으면 새로 넣는다.
  * 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다. 파일에 없는 기존 상품은 지우지 않는다.
  */
 ImportResult importProducts(String shop,String title,String portrait,List<ImportEntry> entries)throws Exception{return importProducts(shop,title,portrait,"","",entries);}
 ImportResult importProducts(String shop,String title,String portrait,String owner,String role,List<ImportEntry> entries)throws Exception{
  for(String v:List.of(owner,role))if(v.length()>40||v.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("주인 이름·역할은 40자 이하로 적어 주세요.");
  if(!shop.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("상점 ID는 영문 소문자·숫자·_·- 로 48자까지예요.");
  if(title.isBlank()||title.length()>80)throw new IllegalArgumentException("상점 이름은 1~80자예요.");
  if(!portrait.isEmpty()&&(!portrait.matches("[a-z0-9_/-]{1,120}")||portrait.contains("..")))throw new IllegalArgumentException("초상 ID는 영문 소문자·숫자·_·-·/ 만 써요.");
  var names=new HashSet<String>();for(var e:entries){adminValues(e.name(),e.bytes(),e.buy(),e.sell());sortValues(e.category(),e.sort());ShopExchange.validate(e.costItem(),e.costCount());if(!names.add(e.name()))throw new IllegalArgumentException("파일 안에 같은 이름이 두 번 있어요: "+e.name());}
  return tx(()->{boolean created;try(var s=db().prepareStatement("SELECT id FROM "+shops+" WHERE id=?")){s.setString(1,shop);try(var q=s.executeQuery()){created=!q.next();}}
   if(created)try(var s=db().prepareStatement("INSERT INTO "+shops+"(id,title,portrait,revision) VALUES(?,?,?,1)")){s.setString(1,shop);s.setString(2,title);s.setString(3,portrait.isEmpty()?"elena-neutral":portrait);s.executeUpdate();}
   else{try(var s=db().prepareStatement("UPDATE "+shops+" SET title=? WHERE id=?")){s.setString(1,title);s.setString(2,shop);s.executeUpdate();}if(!portrait.isEmpty())try(var s=db().prepareStatement("UPDATE "+shops+" SET portrait=? WHERE id=?")){s.setString(1,portrait);s.setString(2,shop);s.executeUpdate();}}
   try(var s=db().prepareStatement("UPDATE "+shops+" SET owner=?,role=? WHERE id=?")){s.setString(1,owner.strip());s.setString(2,role.strip());s.setString(3,shop);s.executeUpdate();}
   bump(shop);var existing=new HashMap<String,String>();try(var s=db().prepareStatement("SELECT id,name FROM "+products+" WHERE shop=?")){s.setString(1,shop);try(var q=s.executeQuery()){while(q.next())existing.putIfAbsent(q.getString(2),q.getString(1));}}
   int added=0,updated=0;for(var e:entries){String id=existing.get(e.name());if(id==null){id=insert(shop,e.name(),e.bytes(),e.buy(),e.sell(),e.category().strip(),e.sort());added++;}
    else{try(var s=db().prepareStatement("UPDATE "+products+" SET buy=?,sell=?,category=?,sort_order=? WHERE shop=? AND id=?")){s.setString(1,e.buy());s.setString(2,e.sell());s.setString(3,e.category().strip());s.setInt(4,e.sort());s.setString(5,shop);s.setString(6,id);s.executeUpdate();}updated++;}
    // 교환 칸: 파일에 적힌 대로(없으면 해제). 파일이 기준이다.
    try(var s=db().prepareStatement("UPDATE "+products+" SET cost_item=?,cost_count=? WHERE shop=? AND id=?")){s.setString(1,e.costItem());s.setInt(2,e.costCount());s.setString(3,shop);s.setString(4,id);s.executeUpdate();}}
   return new ImportResult(created,added,updated);});
 }
 Order prepare(UUID owner,String request,String shop,long revision,String product,int quantity,boolean selling)throws Exception{return prepare(owner,request,shop,revision,product,quantity,selling,"");}
 /** 거래 기록만 만든다(부작용 없음). 카탈로그 버전은 올리지 않고, 필요한 상점·상품 한 건만 읽는다. */
 Order prepare(UUID owner,String request,String shop,long revision,String product,int quantity,boolean selling,String origin)throws Exception{return tx(false,()->{
  if(quantity<1||quantity>576)throw new IllegalArgumentException("수량 한도");
  try(var s=db().prepareStatement("SELECT id FROM "+orders+" WHERE request_key=?")){s.setString(1,request);try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("이미 처리한 요청입니다. 우편/인벤토리를 확인해 주세요.");}}
  try(var s=db().prepareStatement("SELECT id FROM "+orders+" WHERE owner=? AND state NOT IN ('done','failed')")){s.setString(1,owner.toString());try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("이전 거래 기록 확인이 필요합니다. 상점을 다시 열어도 계속되면 관리자에게 문의해 주세요.");}}
  String title;try(var s=db().prepareStatement("SELECT title,revision FROM "+shops+" WHERE id=?")){s.setString(1,shop);try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=revision)throw new IllegalStateException("상점 설정이 변경되었습니다. 다시 열어 주세요.");title=r.getString(1);}}
  byte[] bytes;String unit,costItem;int costCount;try(var s=db().prepareStatement("SELECT payload,buy,sell,cost_item,cost_count FROM "+products+" WHERE shop=? AND id=?")){s.setString(1,shop);s.setString(2,product);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("상점 설정이 변경되었습니다. 다시 열어 주세요.");bytes=r.getBytes(1);unit=r.getString(selling?3:2);costItem=r.getString(4);costCount=r.getInt(5);}}
  // 교환 상품: 돈 대신 비용 아이템(개수 × 수량). 판매는 막는다.
  boolean exchange=costCount>0;if(exchange){if(selling)throw new IllegalStateException("교환 상품은 팔 수 없어요.");ShopExchange.validate(costItem,costCount);unit=String.valueOf(costCount);}
  if(unit.isEmpty())throw new IllegalStateException("허용하지 않는 거래입니다.");BigDecimal total=new BigDecimal(unit).multiply(BigDecimal.valueOf(quantity));if(total.compareTo(new BigDecimal("1000000000000"))>0)throw new IllegalArgumentException("합계 한도 초과");String id=UUID.randomUUID().toString();long created=System.currentTimeMillis();Order o=new Order(id,owner,"prepared",exchange?"exchange":selling?"sell":"buy",product,quantity,total.toPlainString(),bytes,title,origin,created,exchange?costItem:"");
  try(var s=db().prepareStatement("INSERT INTO "+orders+"(id,owner,request_key,state,mode,product,quantity,total,payload,title,created,origin,cost) VALUES(?,?,?,'prepared',?,?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,owner.toString());s.setString(3,request);s.setString(4,o.mode);s.setString(5,product);s.setInt(6,quantity);s.setString(7,o.total);s.setBytes(8,bytes);s.setString(9,title);s.setLong(10,created);s.setString(11,origin);s.setString(12,o.cost);s.executeUpdate();}return o;
 });}
 /** 상태 비교-교체(CAS). 한 문장이라 원자적이며, 진 쪽은 예외를 받고 부작용을 실행하지 않는다. */
 void state(String id,String expected,String next)throws Exception{if(!move(id,List.of(expected),next))throw new IllegalStateException("거래 상태가 이미 변경되었습니다. 상점을 다시 열어 주세요.");}
 /** from 중 하나일 때만 next 로 바꾼다. 바뀌었으면 true. */
 boolean move(String id,List<String> from,String next)throws SQLException{if(from.isEmpty()||from.stream().anyMatch(v->!v.matches("[a-z_]{1,20}")))throw new IllegalArgumentException("state");try(var s=db().prepareStatement("UPDATE "+orders+" SET state=? WHERE id=? AND state IN ('"+String.join("','",from)+"')")){s.setString(1,next);s.setString(2,id);return s.executeUpdate()==1;}}
 /** 관리자 처리용: from 상태일 때만 next 로 바꾸고 바꾸기 전 기록을 돌려준다. 같은 처리를 두 번 눌러도 한 번만 성공한다. */
 Order adminMove(String id,List<String> from,String next)throws Exception{return tx(false,()->{Order o=order(id);if(o==null)throw new IllegalArgumentException("거래 기록이 없습니다.");if(!from.contains(o.state)||!o.state.equals(next)&&!move(id,List.of(o.state),next))throw new IllegalStateException("이 상태("+o.state+")에서는 할 수 없는 처리입니다. 기록을 다시 확인하세요.");return o;});}
 private static final String ORDER_COLUMNS="id,owner,state,mode,product,quantity,total,payload,title,origin,created,cost";
 private static Order order(ResultSet r)throws SQLException{return new Order(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getString(7),r.getBytes(8),r.getString(9),r.getString(10),r.getLong(11),r.getString(12));}
 Order order(String id)throws SQLException{try(var s=db().prepareStatement("SELECT "+ORDER_COLUMNS+" FROM "+orders+" WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next()?order(r):null;}}}
 /** 끝나지 않은(done/failed 가 아닌) 거래 전부. 자동 복구와 관리자 목록이 함께 쓴다. */
 List<Order> open(UUID owner)throws SQLException{var out=new ArrayList<Order>();try(var s=db().prepareStatement("SELECT "+ORDER_COLUMNS+" FROM "+orders+" WHERE owner=? AND state NOT IN ('done','failed') ORDER BY created,id")){s.setString(1,owner.toString());try(var r=s.executeQuery()){while(r.next())out.add(order(r));}}return out;}
 List<Order> paid(UUID owner)throws SQLException{return open(owner).stream().filter(o->o.state.equals("paid")&&o.mode.equals("buy")).toList();}
 /** 서버 시작 시: 이 서버가 만든 구매 기록 중 출금 전(prepared)인 것은 부작용이 없으므로 실패로 닫는다.
  * 교환(exchange) prepared 는 닫지 않는다: 비용 아이템 회수와 영수증(PDC)이 같은 틱에 기록되므로, 유저가 접속했을 때 영수증 유무로 판정한다(판매와 같음). */
 int failPrepared(String origin)throws SQLException{if(origin.isEmpty())return 0;try(var s=db().prepareStatement("UPDATE "+orders+" SET state='failed' WHERE state='prepared' AND mode='buy' AND origin=?")){s.setString(1,origin);return s.executeUpdate();}}

 // ------------------------------------------------------------------ 튜토리얼 전용 재료 상점 (2026-10-25, 메모 #12)
 /** 튜토리얼 상점 대상이면 구매를 세기 시작한 시각, 아니면 0. */
 long tutorialStarted(UUID owner)throws SQLException{try(var s=db().prepareStatement("SELECT started FROM "+tutorial+" WHERE owner=?")){s.setString(1,owner.toString());try(var r=s.executeQuery()){return r.next()?Math.max(1,r.getLong(1)):0;}}}
 /** 대상으로 지정. 이미 대상이면 그대로 둔다(스토리 시작명령이 여러 번 실행돼도 구매 기록 유지). reset = 지금부터 다시 센다. 시작 시각을 돌려준다. */
 long tutorialStart(UUID owner,boolean reset)throws Exception{return tx(false,()->{long now=System.currentTimeMillis();
  if(reset)try(var s=db().prepareStatement("UPDATE "+tutorial+" SET started=? WHERE owner=?")){s.setLong(1,now);s.setString(2,owner.toString());if(s.executeUpdate()==1)return now;}
  long had=tutorialStarted(owner);if(had>0)return had;
  try(var s=db().prepareStatement("INSERT INTO "+tutorial+"(owner,started) VALUES(?,?)")){s.setString(1,owner.toString());s.setLong(2,now);s.executeUpdate();}return now;});}
 boolean tutorialEnd(UUID owner)throws Exception{return tx(false,()->{try(var s=db().prepareStatement("DELETE FROM "+tutorial+" WHERE owner=?")){s.setString(1,owner.toString());return s.executeUpdate()==1;}});}
 /** since 이후 이 유저가 결제를 마친(paid·done) 구매 수량, 상품 ID별. productIds 밖의 상품은 세지 않는다. */
 Map<String,Integer> bought(UUID owner,Collection<String> productIds,long since)throws SQLException{var out=new HashMap<String,Integer>();if(productIds.isEmpty())return out;
  try(var s=db().prepareStatement("SELECT product,SUM(quantity) FROM "+orders+" WHERE owner=? AND mode='buy' AND state IN ('paid','done') AND created>=? GROUP BY product")){s.setString(1,owner.toString());s.setLong(2,since);try(var r=s.executeQuery()){while(r.next())if(productIds.contains(r.getString(1)))out.put(r.getString(1),r.getInt(2));}}return out;}
 @Override public void close()throws Exception{holder.close();}
}
