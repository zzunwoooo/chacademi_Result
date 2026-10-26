package dev.portablevfx.client;

import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientCommandIdentifierTest {
    @Test void namespacedEffectIdIsParsedInFull() throws Exception {
        StringReader input = new StringReader("portablevfx:magic_circle");
        Identifier result = IdentifierArgumentType.identifier().parse(input);
        assertEquals(Identifier.of("portablevfx", "magic_circle"), result);
        assertEquals("", input.getRemaining());
    }
}
