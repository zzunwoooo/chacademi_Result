package school.magiccodex.client;
final class HousingSearchTextFit {
 static float buttonSize(float size,float width,float iconWidth,float textWidth){return Math.min(size,size*Math.max(1,width-iconWidth-12)/Math.max(1,textWidth));}
 private HousingSearchTextFit(){}
}
