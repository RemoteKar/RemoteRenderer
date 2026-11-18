package kr.gcb.remoterenderer.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The frustum's planes: a sphere test is far cheaper than Frustum.isVisible's box test, per display per frame. */
@Mixin(Frustum.class)
public interface FrustumAccessor {
    @Accessor("intersection")
    FrustumIntersection remoterenderer$intersection();
}
