package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.render.MeshFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the instanced mesh renderer next to the vanilla feature renderers. */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
    @Shadow
    @Final
    private FeatureRendererMap featureRenderers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void remoterenderer$register(CallbackInfo ci) {
        this.featureRenderers.put(MeshFeatureRenderer.TYPE, new MeshFeatureRenderer());
        this.featureRenderers.put(kr.gcb.remoterenderer.render.RigRenderer.TYPE, new kr.gcb.remoterenderer.render.RigRenderer());
    }
}
