package kr.gcb.remoterenderer.mixin;

import java.util.List;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CuboidItemModelWrapper.class)
public interface CuboidItemModelWrapperAccessor {
    @Accessor("tints")
    List<ItemTintSource> remoterenderer$tints();

    @Accessor("itemQuads")
    ItemQuads remoterenderer$itemQuads();
}
