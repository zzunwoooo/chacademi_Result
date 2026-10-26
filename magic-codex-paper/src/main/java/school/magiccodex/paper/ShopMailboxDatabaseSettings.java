package school.magiccodex.paper;

import java.io.IOException;
import java.nio.file.Path;
import school.magiccodex.database.DatabaseSettings;

/** Only shop/mailbox use this configuration; no shared-settings fallback or migration. */
final class ShopMailboxDatabaseSettings {
    static final String FILE_NAME = "shop-mailbox-database.properties";
    private ShopMailboxDatabaseSettings() {}
    static DatabaseSettings load(Path pluginDirectory) throws IOException {
        return DatabaseSettings.load(pluginDirectory.resolve(FILE_NAME));
    }
}