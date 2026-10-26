package school.magiccodex.paper;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.*;
import school.magiccodex.protocol.MailboxProtocol.Entry;

/** One IO worker. All owner writes fence through a DB row; no automatic replay of external item delivery. */
final class MailboxStore implements AutoCloseable {
 record Item(int index,String name,int amount,byte[] bytes,String state,String token,String origin){}
 record Mail(String id,String body,List<Item> items){}
 record Page(List<Entry> entries,boolean more){}
 record Ref(String mail,Item item){}
 /** 수령 도중(pending) 멈춘 첨부 한 건: 관리자 목록용. */
 record Held(String mail,String title,long created,Item item){}
 private final ConnectionHolder holder;private final String mail,item,owners;
 MailboxStore(DatabaseSettings settings,Path file)throws Exception{
  holder=settings.holder(file);mail=settings.table("mail","mail");item=settings.table("mail_items","mail_items");owners=settings.table("mail_owners","mail_owners");
  String tail=settings.mariaDb()?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";
  try(var s=db().createStatement()){
   s.execute("CREATE TABLE IF NOT EXISTS "+owners+"(owner VARCHAR(36) PRIMARY KEY,revision BIGINT NOT NULL)"+tail);
   s.execute("CREATE TABLE IF NOT EXISTS "+mail+"(id VARCHAR(36) PRIMARY KEY,owner VARCHAR(36) NOT NULL,source_key VARCHAR(160) NOT NULL UNIQUE,title VARCHAR(80) NOT NULL,body TEXT NOT NULL,created BIGINT NOT NULL,deleted BIGINT NOT NULL DEFAULT 0)"+tail);
   s.execute("CREATE TABLE IF NOT EXISTS "+item+"(mail VARCHAR(36) NOT NULL,idx INTEGER NOT NULL,name VARCHAR(120) NOT NULL,amount INTEGER NOT NULL,payload BLOB NOT NULL,state VARCHAR(12) NOT NULL,token VARCHAR(36) NOT NULL,origin VARCHAR(36) NOT NULL,PRIMARY KEY(mail,idx))"+tail);
  }
  // 추가 전용 색인 (SQLite·MariaDB 공통 문법). 성능용이라 만들지 못해도 동작에는 영향이 없다.
  for(String sql:List.of("CREATE INDEX IF NOT EXISTS "+mail+"_owner_created ON "+mail+"(owner,deleted,created)","CREATE INDEX IF NOT EXISTS "+item+"_token ON "+item+"(token)"))try(var s=db().createStatement()){s.execute(sql);}catch(SQLException ignored){}
 }
 private Connection db()throws SQLException{return holder.get();}
 private interface Work<T>{T run()throws Exception;}
 private <T>T tx(UUID owner,Work<T> work)throws Exception{
  Connection c=holder.begin();try{
   try(var s=c.prepareStatement("INSERT INTO "+owners+"(owner,revision) VALUES(?,0)")){s.setString(1,owner.toString());try{s.executeUpdate();}catch(SQLException e){if(!constraint(e))throw e;}}
   try(var s=c.prepareStatement("UPDATE "+owners+" SET revision=revision+1 WHERE owner=?")){s.setString(1,owner.toString());if(s.executeUpdate()!=1)throw new SQLException("owner fence");}
   T result=work.run();c.commit();return result;
  }catch(Exception e){holder.rollback();throw e;}finally{holder.end();}
 }
 private static boolean constraint(SQLException e){return e.getErrorCode()==19||e.getErrorCode()==1062||"23505".equals(e.getSQLState());}
 UUID create(String source,UUID owner,String title,String body,List<Item> items)throws Exception{return tx(owner,()->{
  try(var s=db().prepareStatement("SELECT id,owner FROM "+mail+" WHERE source_key=?")){s.setString(1,source);try(var r=s.executeQuery()){if(r.next()){if(!owner.toString().equals(r.getString(2)))throw new SQLException("source owner mismatch");return UUID.fromString(r.getString(1));}}}
  UUID id=UUID.randomUUID();try(var s=db().prepareStatement("INSERT INTO "+mail+"(id,owner,source_key,title,body,created,deleted) VALUES(?,?,?,?,?,?,0)")){s.setString(1,id.toString());s.setString(2,owner.toString());s.setString(3,source);s.setString(4,title);s.setString(5,body);s.setLong(6,System.currentTimeMillis());s.executeUpdate();}
  for(Item i:items)try(var s=db().prepareStatement("INSERT INTO "+item+"(mail,idx,name,amount,payload,state,token,origin) VALUES(?,?,?,?,?,'ready','','')")){s.setString(1,id.toString());s.setInt(2,i.index);s.setString(3,i.name);s.setInt(4,i.amount);s.setBytes(5,i.bytes);s.executeUpdate();}return id;
 });}
 Page list(UUID owner,int page)throws SQLException{
  var entries=new ArrayList<Entry>();String sql="SELECT m.id,m.title,m.created,COUNT(i.idx),SUM(CASE WHEN i.state<>'claimed' THEN 1 ELSE 0 END) FROM "+mail+" m LEFT JOIN "+item+" i ON i.mail=m.id WHERE m.owner=? AND m.deleted=0 GROUP BY m.id,m.title,m.created ORDER BY m.created DESC,m.id LIMIT 21 OFFSET ?";
  try(var s=db().prepareStatement(sql)){s.setString(1,owner.toString());s.setInt(2,Math.multiplyExact(page,20));try(var r=s.executeQuery()){while(r.next())entries.add(new Entry(r.getString(1),r.getString(2),r.getLong(3),r.getInt(5),r.getInt(4)));}}boolean more=entries.size()>20;if(more)entries.removeLast();return new Page(List.copyOf(entries),more);
 }
 Mail detail(UUID owner,String id)throws SQLException{
  try(var s=db().prepareStatement("SELECT body FROM "+mail+" WHERE id=? AND owner=? AND deleted=0")){s.setString(1,id);s.setString(2,owner.toString());try(var r=s.executeQuery()){if(!r.next())return null;return new Mail(id,r.getString(1),items(id));}}
 }
 private List<Item> items(String id)throws SQLException{var out=new ArrayList<Item>();try(var s=db().prepareStatement("SELECT idx,name,amount,payload,state,token,origin FROM "+item+" WHERE mail=? ORDER BY idx")){s.setString(1,id);try(var r=s.executeQuery()){while(r.next())out.add(new Item(r.getInt(1),r.getString(2),r.getInt(3),r.getBytes(4),r.getString(5),r.getString(6),r.getString(7)));}}return List.copyOf(out);}
 private static final String ITEM_COLUMNS="i.mail,i.idx,i.name,i.amount,i.payload,i.state,i.token,i.origin";
 private static Item item(ResultSet r)throws SQLException{return new Item(r.getInt(2),r.getString(3),r.getInt(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8));}
 /** 우편마다 첨부를 따로 읽지 않고 한 번의 조인으로 읽는다. */
 List<Ref> ready(UUID owner,String selected)throws SQLException{var out=new ArrayList<Ref>();String sql="SELECT "+ITEM_COLUMNS+" FROM "+item+" i JOIN "+mail+" m ON m.id=i.mail WHERE m.owner=? AND m.deleted=0 AND i.state='ready'"+(selected.isEmpty()?"":" AND m.id=?")+" ORDER BY m.created,m.id,i.idx";try(var s=db().prepareStatement(sql)){s.setString(1,owner.toString());if(!selected.isEmpty())s.setString(2,selected);try(var r=s.executeQuery()){while(r.next())out.add(new Ref(r.getString(1),item(r)));}}return List.copyOf(out);}
 List<Ref> pending(UUID owner)throws SQLException{var out=new ArrayList<Ref>();try(var s=db().prepareStatement("SELECT "+ITEM_COLUMNS+" FROM "+item+" i JOIN "+mail+" m ON m.id=i.mail WHERE m.owner=? AND i.state='pending' ORDER BY m.created,m.id,i.idx")){s.setString(1,owner.toString());try(var r=s.executeQuery()){while(r.next())out.add(new Ref(r.getString(1),item(r)));}}return out;}
 List<Held> held(UUID owner)throws SQLException{var out=new ArrayList<Held>();try(var s=db().prepareStatement("SELECT "+ITEM_COLUMNS+",m.title,m.created FROM "+item+" i JOIN "+mail+" m ON m.id=i.mail WHERE m.owner=? AND i.state='pending' ORDER BY m.created,m.id,i.idx")){s.setString(1,owner.toString());try(var r=s.executeQuery()){while(r.next())out.add(new Held(r.getString(1),r.getString(9),r.getLong(10),item(r)));}}return out;}
 void reserve(UUID owner,List<Ref> refs,String token,String origin)throws Exception{tx(owner,()->{if(!pending(owner).isEmpty())throw new IllegalStateException("이전 수령 기록 확인이 필요합니다.");for(Ref ref:refs)try(var s=db().prepareStatement("UPDATE "+item+" SET state='pending',token=?,origin=? WHERE mail=? AND idx=? AND state='ready' AND EXISTS(SELECT 1 FROM "+mail+" m WHERE m.id=? AND m.owner=? AND m.deleted=0)")){s.setString(1,token);s.setString(2,origin);s.setString(3,ref.mail);s.setInt(4,ref.item.index);s.setString(5,ref.mail);s.setString(6,owner.toString());if(s.executeUpdate()!=1)throw new IllegalStateException("우편이 변경되었습니다. 다시 확인해 주세요.");}return null;});}
 /** pending 인 첨부만 claimed(수령 완료) 또는 ready(다시 수령 가능)로 바꾼다. 같은 token 으로 두 번 불러도 한 번만 바뀐다. 바뀐 첨부 수를 돌려준다. */
 int settle(UUID owner,String token,boolean claimed)throws Exception{if(token==null||token.isEmpty())return 0;return tx(owner,()->{try(var s=db().prepareStatement("UPDATE "+item+" SET state=?,token='',origin='' WHERE token=? AND state='pending' AND EXISTS(SELECT 1 FROM "+mail+" m WHERE m.id="+item+".mail AND m.owner=?)")){s.setString(1,claimed?"claimed":"ready");s.setString(2,token);s.setString(3,owner.toString());return s.executeUpdate();}});}
 int deleteClaimed(UUID owner)throws Exception{return tx(owner,()->{try(var s=db().prepareStatement("UPDATE "+mail+" SET deleted=? WHERE owner=? AND deleted=0 AND NOT EXISTS(SELECT 1 FROM "+item+" i WHERE i.mail="+mail+".id AND i.state<>'claimed')")){s.setLong(1,System.currentTimeMillis());s.setString(2,owner.toString());return s.executeUpdate();}});}
 /** 관리자 삭제: 받는 중(pending) 첨부가 없는 우편만 지운다(숨김). 지웠으면 true. */
 boolean adminDelete(UUID owner,String id)throws Exception{return tx(owner,()->{try(var s=db().prepareStatement("UPDATE "+mail+" SET deleted=? WHERE id=? AND owner=? AND deleted=0 AND NOT EXISTS(SELECT 1 FROM "+item+" i WHERE i.mail="+mail+".id AND i.state='pending')")){s.setLong(1,System.currentTimeMillis());s.setString(2,id);s.setString(3,owner.toString());return s.executeUpdate()==1;}});}
 boolean restore(UUID owner,UUID id)throws Exception{return tx(owner,()->{try(var s=db().prepareStatement("UPDATE "+mail+" SET deleted=0 WHERE id=? AND owner=? AND deleted<>0")){s.setString(1,id.toString());s.setString(2,owner.toString());return s.executeUpdate()==1;}});}
 @Override public void close()throws Exception{holder.close();}
}
