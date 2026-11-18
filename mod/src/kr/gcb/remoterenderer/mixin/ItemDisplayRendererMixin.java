package kr.gcb.remoterenderer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.duck.RrRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public abstract class ItemDisplayRendererMixin {
    @Shadow
    @Final
    private ItemModelResolver itemModelResolver;

    @WrapOperation(
        method = "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForNonLiving(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/entity/Entity;)V")
    )
    private void remoterenderer$extract(ItemModelResolver resolver, ItemStackRenderState out, ItemStack stack, ItemDisplayContext context, Entity entity,
                                     Operation<Void> original, @Local(argsOnly = true) ItemDisplayEntityRenderState state) {
        RemoteRenderer.extract(resolver, out, stack, context, entity, state, original);
    }

    @Inject(
        method = "submitInner(Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
        at = @At("HEAD"), cancellable = true
    )
    private void remoterenderer$submit(ItemDisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                    float interpolationProgress, CallbackInfo ci) {
        RrRenderState rr = (RrRenderState) state;
        if (rr.remoterenderer$entry() == null && rr.remoterenderer$rig() == null) {
            return;
        }
        if (RemoteRenderer.submit(state, poseStack, collector, lightCoords)) {
            ci.cancel();
        } else {
            RemoteRenderer.resolveLate(this.itemModelResolver, state);
        }
    }
}
