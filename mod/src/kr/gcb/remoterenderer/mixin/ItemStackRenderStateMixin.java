package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.ResolvePath;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every item model calls appendModelIdentityElement(this) while it resolves (a no-op in the base class, used by
 * the GUI's tracking state). While RemoteRenderer resolves a display's item, it records them: the path the resolution
 * took tells whether the result depends on the item stack alone.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin {
    @Inject(method = "appendModelIdentityElement", at = @At("HEAD"))
    private void remoterenderer$record(Object element, CallbackInfo ci) {
        ResolvePath.record(element);
    }
}
