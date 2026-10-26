package school.magiccodex.paper;
/** A full supported tank must fit in the protocol's nonnegative long total. */
final class FishShopPricing {
 private FishShopPricing(){}
 static void validate(double base,double min,double max){
  double ceiling=base*max;
  if(!Double.isFinite(base)||!Double.isFinite(min)||!Double.isFinite(max)||base<0||min<0||max<min||!Double.isFinite(ceiling)||ceiling>Long.MAX_VALUE/(double)school.magiccodex.protocol.FishTankProtocol.MAX_FISH)
   throw new IllegalArgumentException("Fish price must be finite, nonnegative and fit a full tank total");
 }
}
