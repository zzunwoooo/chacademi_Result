package school.magiccodex.party;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class InstalledOriginTest {@Test void installedCoreIsUsedWhenRequested(){if(Boolean.getBoolean("chacademi.audit.installed")){assertTrue(NetworkChat.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("commands.1.jar"));assertTrue(PartyPlugin.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("commands.1.jar"));}}}
