package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.duck.RrEntity;
import kr.gcb.remoterenderer.mesh.EntityCache;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every item display a place to remember what its item resolved to. */
@Mixin(Display.ItemDisplay.class)
public abstract class ItemDisplayMixin implements RrEntity {
    @Unique
    private final EntityCache remoterenderer$cache = new EntityCache();

    @Override
    public EntityCache remoterenderer$cache() {
        return this.remoterenderer$cache;
    }
}
