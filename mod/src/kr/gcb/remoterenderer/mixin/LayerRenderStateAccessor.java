package kr.gcb.remoterenderer.mixin;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("quads")
    ItemQuads remoterenderer$quads();

    @Accessor("itemTransform")
    ItemTransform remoterenderer$itemTransform();

    @Accessor("localTransform")
    Matrix4f remoterenderer$localTransform();

    @Accessor("tintLayers")
    IntList remoterenderer$tintLayers();

    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> remoterenderer$specialRenderer();

    @Accessor("foilType")
    ItemStackRenderState.FoilType remoterenderer$foilType();
}
