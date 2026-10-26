package school.magiccodex.client;
final class PortraitPreviewLayout {
 record Box(int x,int y,int width,int height){}
 static Box fit(int screenWidth,int screenHeight,int imageWidth,int imageHeight){
  int margin=Math.min(24,screenWidth/10),top=Math.min(64,screenHeight/5),bottom=Math.min(60,screenHeight/5);
  int availableW=Math.max(1,screenWidth-2*margin),availableH=Math.max(1,screenHeight-top-bottom);
  double scale=Math.min(availableW/(double)Math.max(1,imageWidth),availableH/(double)Math.max(1,imageHeight));
  int w=Math.max(1,(int)Math.floor(imageWidth*scale)),h=Math.max(1,(int)Math.floor(imageHeight*scale));
  return new Box((screenWidth-w)/2,top+(availableH-h)/2,w,h);
 }
}
