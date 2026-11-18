package kr.gcb.remoterenderer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.gcb.remoterenderer.FastEntities;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The fast entity path (FastEntities, config fastEntities) for item displays RemoteRenderer draws. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z", at = @At("HEAD"), cancellable = true)
    private void remoterenderer$cull(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (FastEntities.culled(entity, frustum, partialTicks)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("HEAD"),
        cancellable = true)
    private void remoterenderer$extract(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = FastEntities.extract((EntityRenderDispatcher) (Object) this, entity, partialTicks);
        if (state != null) {
            cir.setReturnValue(state);
        }
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD"), cancellable = true)
    private void remoterenderer$submit(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
                                       SubmitNodeCollector collector, CallbackInfo ci) {
        if (FastEntities.submit(state, x, y, z, poseStack, collector)) {
            ci.cancel();
        }
    }
}
