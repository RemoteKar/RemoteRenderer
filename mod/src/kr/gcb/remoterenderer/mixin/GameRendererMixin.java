package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.RemoteRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render()V", at = @At("HEAD"))
    private void remoterenderer$frame(CallbackInfo ci) {
        RemoteRenderer.STATS.onFrame();
        kr.gcb.remoterenderer.mesh.MeshCache.poll();
        // every frame, also when no rig is drawn (rig textures nobody uses leave the GPU)
        kr.gcb.remoterenderer.rig.RigManager.endFrame(System.nanoTime());
        kr.gcb.remoterenderer.render.RigRenderer.compatFrame();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (RemoteRenderer.openMenu && mc.level != null && mc.gui.screen() == null) {
            RemoteRenderer.openMenu = false;
            mc.gui.setScreen(new kr.gcb.remoterenderer.RrConfigScreen(null));
        }
    }
}
