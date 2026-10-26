package school.magiccodex.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

/**
 * Player faces for the 큐디치 windows: the tab list when the player is on this server, else the skin hash the proxy sent
 * (textures.minecraft.net, same as the party window), else the default skin.
 */
final class KyudichiFaces {
    private KyudichiFaces() {}
    private record Key(UUID id, String hash) {}
    private static final Map<Key, Supplier<SkinTextures>> skins = new LinkedHashMap<>(64, .75f, true);

    static Identifier face(UUID id, String hash) {
        var c = MinecraftClient.getInstance();
        var entry = c.getNetworkHandler() == null ? null : c.getNetworkHandler().getPlayerListEntry(id);
        if (entry != null) return entry.getSkinTextures().texture();
        if (hash == null || !hash.matches("[0-9a-f]{32,64}")) return DefaultSkinHelper.getSkinTextures(id).texture();
        var key = new Key(id, hash);
        var supplier = skins.get(key);
        if (supplier == null) {
            while (skins.size() >= 128) skins.remove(skins.keySet().iterator().next());
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
            var profile = new GameProfile(id, "KyudichiPlayer");
            profile.getProperties().put("textures", new Property("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
            supplier = c.getSkinProvider().getSkinTexturesSupplier(profile);
            skins.put(key, supplier);
        }
        return supplier.get().texture();
    }
}
