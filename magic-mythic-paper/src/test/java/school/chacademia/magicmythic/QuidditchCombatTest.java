package school.chacademia.magicmythic;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #33 큐디치 전투 보정: 순수 계산 (상자 키우기, 유도 놓침 판정, 설정 읽기). */
class QuidditchCombatTest {
    @Test
    void hitboxGrowsAroundItsCenter() {
        var player = new BoundingBox(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        var big = QuidditchCombat.scaled(player, 1.5);
        assertEquals(0.9, big.getWidthX(), 1e-9);
        assertEquals(2.7, big.getHeight(), 1e-9);
        assertEquals(player.getCenter(), big.getCenter());
        assertEquals(player, QuidditchCombat.scaled(player, 1));
    }

    @Test
    void homingKeepsChasingUnlessTeleportOrLastMomentDodge() {
        var q = new QuidditchCombat(true, 1.5, 1.5, true, 4, 3, 0.6, 5);
        Vector straight = new Vector(0, 0, 1.2);
        // 멀리서 방향을 바꿔도 계속 따라간다
        assertFalse(QuidditchCombat.lost(q, 1.2, straight, new Vector(1.2, 0, 0), 30, 2.7));
        // 맞기 직전(3틱 안 거리)의 급선회 = 회피 성공
        assertTrue(QuidditchCombat.lost(q, 1.2, straight, new Vector(1.2, 0, 0), 6, 2.7));
        // 맞기 직전이라도 그대로 날면 맞는다
        assertFalse(QuidditchCombat.lost(q, 1.2, straight, new Vector(0, 0, 1.25), 6, 2.7));
        // 순간이동은 거리와 상관없이 놓침
        assertTrue(QuidditchCombat.lost(q, 8, straight, straight, 40, 2.7));
        // 빗자루 속도를 모르면(걷는 중) 순간이동만 본다
        assertFalse(QuidditchCombat.lost(q, 0.3, null, null, 2, 2.7));
    }

    @Test
    void configDefaultsAndBounds() throws Exception {
        var none = QuidditchCombat.load(null);
        assertEquals(1.5, none.projectileSpeed);
        assertEquals(1.5, none.flyingHitbox);
        assertEquals(100, none.homingTicks);
        var y = new YamlConfiguration();
        y.loadFromString("q:\n  projectile-speed: 9\n  flying-hitbox: 0.5\n  homing-seconds: 2\n  enabled: false\n");
        var c = QuidditchCombat.load(y.getConfigurationSection("q"));
        assertEquals(4, c.projectileSpeed);
        assertEquals(1, c.flyingHitbox);
        assertEquals(40, c.homingTicks);
        assertFalse(c.enabled);
    }
}
