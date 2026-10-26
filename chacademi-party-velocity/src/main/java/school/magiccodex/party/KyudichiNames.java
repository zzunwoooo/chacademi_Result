package school.magiccodex.party;
import java.sql.*;import java.util.*;
final class KyudichiNames {
 static String display(Connection c,String table,UUID id,String fallback)throws SQLException{
  try(var s=c.prepareStatement("SELECT nickname,account FROM "+table+" WHERE owner=?")){s.setString(1,id.toString());try(var row=s.executeQuery()){if(row.next()){var nick=row.getString(1);if(nick!=null&&!nick.isBlank())return nick;}}}return fallback;
 }
 static UUID find(Connection c,String table,String text,boolean prefix)throws SQLException{
  String query=prefix?text.replace("%","").replace("_","")+"%":text;
  try(var s=c.prepareStatement("SELECT owner FROM "+table+" WHERE LOWER(nickname) "+(prefix?"LIKE":"=")+" LOWER(?) OR LOWER(account)=LOWER(?) ORDER BY owner LIMIT 2")){s.setString(1,query);s.setString(2,text);try(var row=s.executeQuery()){if(!row.next())return null;UUID id=UUID.fromString(row.getString(1));return row.next()?null:id;}}
 }
}
