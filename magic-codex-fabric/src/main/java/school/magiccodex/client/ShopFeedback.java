package school.magiccodex.client;
import java.util.LinkedHashSet;
import school.magiccodex.protocol.ShopProtocol;
/** A receipt is audible only for the outstanding trade, once per completed operation. */
final class ShopFeedback {
 private final LinkedHashSet<String> heard=new LinkedHashSet<>();
 boolean success(ShopProtocol.Response r,long sequence,long session,String shop,int action,String operation){
  if(sequence<=0||r.sequence()!=sequence||r.session()!=session||!r.shop().equals(shop)
    ||(action!=ShopProtocol.BUY&&action!=ShopProtocol.SELL)||operation.isEmpty()
    ||!operation.equals(r.completedOperation())||!heard.add(operation))return false;
  if(heard.size()>128)heard.remove(heard.iterator().next());return true;
 }
 void reset(){heard.clear();}
}