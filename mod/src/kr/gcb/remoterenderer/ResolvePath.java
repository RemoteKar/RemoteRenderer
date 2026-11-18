package kr.gcb.remoterenderer;

import java.util.ArrayList;
import java.util.List;
import kr.gcb.remoterenderer.mixin.CuboidItemModelWrapperAccessor;
import kr.gcb.remoterenderer.mixin.ConditionalItemModelAccessor;
import kr.gcb.remoterenderer.mixin.RangeSelectItemModelAccessor;
import kr.gcb.remoterenderer.mixin.SelectItemModelAccessor;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.color.item.Dye;
import net.minecraft.client.color.item.Firework;
import net.minecraft.client.color.item.GrassColorSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.Potion;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ConditionalItemModel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.EmptyModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.item.properties.conditional.Broken;
import net.minecraft.client.renderer.item.properties.conditional.ComponentMatches;
import net.minecraft.client.renderer.item.properties.conditional.Damaged;
import net.minecraft.client.renderer.item.properties.conditional.HasComponent;
import net.minecraft.client.renderer.item.properties.conditional.ItemModelPropertyTest;
import net.minecraft.client.renderer.item.properties.numeric.Count;
import net.minecraft.client.renderer.item.properties.numeric.Damage;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.client.renderer.item.properties.select.Charge;
import net.minecraft.client.renderer.item.properties.select.ComponentContents;
import net.minecraft.client.renderer.item.properties.select.DisplayContext;
import net.minecraft.client.renderer.item.properties.select.ItemBlockState;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.client.renderer.item.properties.select.TrimMaterialProperty;

/**
 * Records the item models a resolution passes through (ItemStackRenderState.appendModelIdentityElement) and judges
 * whether the result can depend on anything but the item stack and the display context. A select / condition /
 * range_dispatch on custom_model_data, a component, damage or count is "stack only"; one on time, the holder, the
 * world, keys, ... is not. Only the path actually taken matters: other branches would need another stack.
 * Render thread only.
 */
public final class ResolvePath {
    private static final List<Object> RECORDED = new ArrayList<>();
    private static boolean recording;
    private static Thread owner;

    private ResolvePath() {
    }

    public static void begin() {
        RECORDED.clear();
        owner = Thread.currentThread();
        recording = true;
    }

    public static void end() {
        recording = false;
    }

    public static void record(Object element) {
        if (recording && Thread.currentThread() == owner) {
            RECORDED.add(element);
        }
    }

    /** Was everything on the recorded path a function of the stack (and display context)? */
    public static boolean stackOnly() {
        boolean any = false;
        for (Object o : RECORDED) {
            if (o instanceof ItemModel model) {
                any = true;
                if (!stackOnly(model)) {
                    return false;
                }
            }
        }
        RECORDED.clear();
        return any;
    }

    private static boolean stackOnly(ItemModel model) {
        if (model instanceof CuboidItemModelWrapper cuboid) {
            for (ItemTintSource tint : ((CuboidItemModelWrapperAccessor) cuboid).remoterenderer$tints()) {
                if (!(tint instanceof Constant || tint instanceof CustomModelDataSource || tint instanceof Dye || tint instanceof Potion
                    || tint instanceof Firework || tint instanceof GrassColorSource)) {
                    return false;
                }
            }
            return true;
        }
        if (model instanceof CompositeModel || model instanceof EmptyModel || model instanceof MissingItemModel || model instanceof SpecialModelWrapper) {
            return true;
        }
        if (model instanceof SelectItemModel<?> select) {
            SelectItemModelProperty<?> p = ((SelectItemModelAccessor) select).remoterenderer$property();
            return p instanceof net.minecraft.client.renderer.item.properties.select.CustomModelDataProperty || p instanceof ComponentContents<?>
                || p instanceof Charge || p instanceof TrimMaterialProperty || p instanceof ItemBlockState || p instanceof DisplayContext;
        }
        if (model instanceof ConditionalItemModel conditional) {
            ItemModelPropertyTest p = ((ConditionalItemModelAccessor) conditional).remoterenderer$property();
            return p instanceof net.minecraft.client.renderer.item.properties.conditional.CustomModelDataProperty || p instanceof HasComponent
                || p instanceof ComponentMatches || p instanceof Damaged || p instanceof Broken;
        }
        if (model instanceof RangeSelectItemModel range) {
            RangeSelectItemModelProperty p = ((RangeSelectItemModelAccessor) range).remoterenderer$property();
            return p instanceof net.minecraft.client.renderer.item.properties.numeric.CustomModelDataProperty || p instanceof Count
                || p instanceof Damage;
        }
        return false;
    }
}
