package dev.portablevfx.client;

import dev.portablevfx.client.api.PortableVfxApi;
import dev.portablevfx.client.definition.EffectLibrary;
import dev.portablevfx.client.network.VfxNetworking;
import dev.portablevfx.client.render.EffectRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PortableVfxClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("PortableVFX");
    private static PortableVfxApi api;

    @Override public void onInitializeClient() {
        try {
            dev.portablevfx.client.claude.ClaudeBloomConfig.load(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("portablevfx/bloom.properties"));
        } catch (java.io.IOException error) {
            LOG.warn("Could not load PortableVFX bloom settings; using strength 0.40", error);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        EffectLibrary library = new EffectLibrary(() -> client.execute(() -> {
            if (api != null) api.clear();
        }));
        EffectRuntime runtime = new EffectRuntime(client, library);
        api = new PortableVfxApi(client, library, runtime);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(library);
        EffectRenderer renderer = new EffectRenderer(runtime, library);
        library.warmup(renderer);
        runtime.readiness(renderer::readyEffectIds);
        var claude=new dev.portablevfx.client.claude.ClaudePreviewController(client,runtime,api,library);
        var drops=new dev.portablevfx.client.drop.DropRarityVfx(client,api);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> { runtime.tick(); claude.tick(); drops.tick(); renderer.synchronizeLifecycle(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> renderer.close());
        // END is after Iris composite/final, unlike LAST inside the world framegraph pass.
        WorldRenderEvents.END.register(renderer::render);
        new VfxNetworking().register(runtime);
        ClientCommands.register(api, library, renderer, claude);
        LOG.info("PortableVFX Claude initialized; protocol v1, Minecraft 1.21.4");
    }

    public static PortableVfxApi api() {
        if (api == null) throw new IllegalStateException("PortableVFX has not initialized yet");
        return api;
    }
}
