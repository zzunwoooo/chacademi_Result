package school.magiccodex.paper;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** /테스트 마법 자동시전: 활성 목록(파일 순서, enabled:false 제외)과 시작점 찾기. */
class SpellReviewRunnerTest {
    @Test void reviewOrderKeepsFileOrderAndSkipsDisabledSpells() throws Exception {
        var catalog = ManaSpells.load(Path.of("src/main/resources/mana-spells.yml").toFile());
        assertEquals(catalog.byId.size(), catalog.order.size());
        assertEquals("feather_fall", catalog.order.get(0));
        assertFalse(catalog.order.contains("aerial_haul")); // 2026-10-26 기획에서 삭제
        assertTrue(catalog.order.indexOf("wind_basket") < catalog.order.indexOf("flight"));
    }

    @Test void startPointByCatalogNumberIdOrName() throws CommandSyntaxException {
        var list = List.of(new SpellReviewRunner.Entry("feather_fall", "깃털 낙하", 1),
            new SpellReviewRunner.Entry("wind_basket", "바람 바구니", 2),
            new SpellReviewRunner.Entry("fish_trap", "아공간 수조", -1));
        assertEquals(1, SpellReviewRunner.find(list, "2"));
        assertEquals(2, SpellReviewRunner.find(list, "fish_trap"));
        assertEquals(1, SpellReviewRunner.find(list, "바람바구니"));
    }
}
