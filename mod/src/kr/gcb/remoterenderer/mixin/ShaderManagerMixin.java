package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.render.MeshPipelines;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compiles RemoteRenderer's pipelines while the game compiles its own (loading screen / resource reload) instead of on
 * the first frame that draws a model, where it cost a ~120 ms hitch.
 */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @Inject(method = "apply", at = @At("TAIL"))
    private void remoterenderer$precompile(CallbackInfo ci) {
        kr.gcb.remoterenderer.RemoteRenderer.checkCustomItemShaders();
        MeshPipelines.precompile();
        kr.gcb.remoterenderer.RemoteRenderer.adaptItemShader();
    }
}
