package kr.chacademy.motionplugin.broom;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BroomClimbPowerTest {
    @Test void existingV2WithoutClimbUsesDefault(){
        var yaml=new YamlConfiguration();
        assertEquals(.055f,BroomType.read("broom_1",yaml).climb(),.000001);
    }
    @Test void commonClimbAndPerBroomOverrideArePreserved(){
        var yaml=new YamlConfiguration();
        assertEquals(.08f,BroomType.read("broom_1",yaml,.08).climb(),.000001);
        yaml.set("climb",.12);
        assertEquals(.12f,BroomType.read("broom_1",yaml,.08).climb(),.000001);
    }
}
