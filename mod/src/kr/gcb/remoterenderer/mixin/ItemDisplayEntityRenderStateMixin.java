package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.duck.RrRenderState;
import kr.gcb.remoterenderer.mesh.EntityCache;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.rig.RigManager;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemDisplayEntityRenderState.class)
public abstract class ItemDisplayEntityRenderStateMixin implements RrRenderState {
    @Unique
    private @Nullable MeshEntry remoterenderer$entry;
    @Unique
    private @Nullable EntityCache remoterenderer$cache;
    @Unique
    private RigManager.@Nullable BoneRef remoterenderer$rig;
    @Unique
    private @Nullable Object remoterenderer$rigInstance;
    @Unique
    private int remoterenderer$rigTint = -1;
    @Unique
    private @Nullable String remoterenderer$rigClip;
    @Unique
    private boolean remoterenderer$rigPhysicsOff;

    @Override
    public boolean remoterenderer$rigPhysicsOff() {
        return this.remoterenderer$rigPhysicsOff;
    }

    @Override
    public RigManager.@Nullable BoneRef remoterenderer$rig() {
        return this.remoterenderer$rig;
    }

    @Override
    public @Nullable Object remoterenderer$rigInstance() {
        return this.remoterenderer$rigInstance;
    }

    @Override
    public int remoterenderer$rigTint() {
        return this.remoterenderer$rigTint;
    }

    @Override
    public @Nullable String remoterenderer$rigClip() {
        return this.remoterenderer$rigClip;
    }

    @Override
    public void remoterenderer$setRig(RigManager.@Nullable BoneRef bone, @Nullable Object instance, int tint, @Nullable String clip, boolean physicsOff) {
        this.remoterenderer$rigPhysicsOff = physicsOff;
        this.remoterenderer$rig = bone;
        this.remoterenderer$rigInstance = instance;
        this.remoterenderer$rigTint = tint;
        this.remoterenderer$rigClip = clip;
    }

    @Override
    public @Nullable MeshEntry remoterenderer$entry() {
        return this.remoterenderer$entry;
    }

    @Override
    public @Nullable EntityCache remoterenderer$cache() {
        return this.remoterenderer$cache;
    }

    @Override
    public void remoterenderer$set(@Nullable MeshEntry entry, @Nullable EntityCache cache) {
        this.remoterenderer$entry = entry;
        this.remoterenderer$cache = cache;
    }

    /** A display whose item was not resolved this frame (cached) still has something to draw. */
    @Inject(method = "hasSubState", at = @At("HEAD"), cancellable = true)
    private void remoterenderer$hasSubState(CallbackInfoReturnable<Boolean> cir) {
        if (this.remoterenderer$entry != null || this.remoterenderer$rig != null) {
            cir.setReturnValue(true);
        }
    }
}
