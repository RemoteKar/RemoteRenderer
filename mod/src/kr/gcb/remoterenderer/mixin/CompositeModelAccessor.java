package kr.gcb.remoterenderer.mixin;

import java.util.List;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompositeModel.class)
public interface CompositeModelAccessor {
    @Accessor("models")
    List<ItemModel> remoterenderer$models();
}
