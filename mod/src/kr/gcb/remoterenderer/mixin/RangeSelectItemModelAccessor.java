package kr.gcb.remoterenderer.mixin;

import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RangeSelectItemModel.class)
public interface RangeSelectItemModelAccessor {
    @Accessor("property")
    RangeSelectItemModelProperty remoterenderer$property();
}
