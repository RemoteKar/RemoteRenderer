package kr.gcb.remoterenderer;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class RemoteRendererClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("remoterenderer.properties");
        RemoteRenderer.configFile = file;
        RemoteRenderer.config = RrConfig.load(file);
        RemoteRenderer.LOG.info("RemoteRenderer loaded (enabled={}, objmc={}, cacheResolve={}, minQuads={})", RemoteRenderer.config.enabled,
            RemoteRenderer.config.objmc, RemoteRenderer.config.cacheResolve, RemoteRenderer.config.minQuads);
        if (FabricLoader.getInstance().isModLoaded("fabric-command-api-v2")) {
            try {
                RrCommands.register();
            } catch (LinkageError e) {
                RemoteRenderer.LOG.warn("Client command not registered: {}", e.toString());
            }
        }
    }
}
