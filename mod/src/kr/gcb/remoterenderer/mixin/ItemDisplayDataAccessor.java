package kr.gcb.remoterenderer.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayDataAccessor {
    @Accessor("DATA_ITEM_DISPLAY_ID")
    static EntityDataAccessor<Byte> remoterenderer$itemDisplay() {
        throw new AssertionError();
    }
}
