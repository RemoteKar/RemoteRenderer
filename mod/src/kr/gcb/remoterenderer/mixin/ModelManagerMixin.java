package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.RemoteRenderer;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** New models (resource reload): the baked meshes belong to the old ones. */
@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {
    @Inject(method = "apply", at = @At("TAIL"))
    private void remoterenderer$reloaded(CallbackInfo ci) {
        RemoteRenderer.onModelsReloaded();
    }
}
