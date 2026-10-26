package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AimPlaneTest {
 @Test void horizontalAndAirArrowsStayStraightCoplanarAndWithinRange(){for(double[] dir:new double[][]{{0,0,1},{1,.7,2},{0,1,0},{0,-1,0},{-4,-2,1}}){var f=AimPlane.frame(dir[0],dir[1],dir[2]);for(var q:AimPlane.arrow(f,24,1.4))for(var v:q){assertEquals(0,v.dot(f.normal()),1e-9);assertTrue(v.dot(f.forward())>=0);assertTrue(v.dot(f.forward())<=24+1e-9);assertTrue(Math.abs(v.dot(f.right()))<=.7+1e-9);}}}
 @Test void tipIsExactlyOnForwardAxisAtMaximumDistance(){var f=AimPlane.frame(1,2,3);var tip=AimPlane.arrow(f,12,3.2).get(1)[2];assertEquals(12,tip.length(),1e-9);assertEquals(12,tip.dot(f.forward()),1e-9);}
 @Test void shortWallClippedArrowNeverExtendsThroughWall(){var f=AimPlane.frame(1,0,0);for(var q:AimPlane.arrow(f,.1,3))for(var v:q)assertTrue(v.dot(f.forward())<=.1+1e-9);}
 @Test void skyFallbackUsesFullThreeDimensionalEyeRange(){double[] end=AimPlane.aim(2,10,3,2,30,23,15);assertEquals(15,Math.sqrt(Math.pow(end[0]-2,2)+Math.pow(end[1]-10,2)+Math.pow(end[2]-3,2)),1e-9);assertTrue(end[1]>10);}
 @Test void boundaryDoesNotDependOnTime(){var f=AimPlane.frame(1,1,1);var a=AimPlane.arrow(f,20,1);var b=AimPlane.arrow(f,20,1);assertArrayEquals(a.get(0),b.get(0));assertArrayEquals(a.get(1),b.get(1));}
 @Test void legacyCursorSettingsCannotRestoreMouseCapture()throws Exception{var p=java.nio.file.Files.createTempFile("aim-cursor", ".properties");java.nio.file.Files.writeString(p,"aim-cursor=true\nsmart-cast=true\n");var s=AimSettings.load(p);assertFalse(s.aimCursor);assertTrue(s.smartCast);}
}
