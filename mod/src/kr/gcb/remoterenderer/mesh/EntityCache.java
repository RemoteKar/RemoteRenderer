package kr.gcb.remoterenderer.mesh;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Per item display: what its item resolved to last time. While the display keeps an equal item and the resolution
 * depended on the stack alone, the item model is not resolved again: BetterModel's mesh bones are composites of
 * thousands of sub-models, and resolving one allocates a layer object per sub-model, every frame.
 */
public final class EntityCache {
    public @Nullable ItemStack stack;
    public @Nullable ItemDisplayContext context;
    public @Nullable ItemModel model;
    public int generation = -1;
    /** What the item resolves to; null = it cannot take the GPU path. */
    public @Nullable MeshKey key;
    /** The baked model of key once it is ready (saves the map lookup). */
    public @Nullable MeshEntry entry;
    /** The resolution depended on the stack (and context) alone. */
    public boolean staticModel;
    /** Tints of the objmc layers, by layer (objmc reads its controls from the item colour). */
    public int[][] objmcLayerTints = MeshCache.NO_LAYER_TINTS;
    /** Set in extract, read in submit when the vanilla path has to be taken late. */
    public @Nullable Entity entity;

    public static final int[] NO_TINTS = new int[0];

    private MeshCache.@Nullable StaticKey staticKey;
    private @Nullable ItemStack staticKeyStack;
    private @Nullable ItemModel staticKeyModel;
    private @Nullable ItemDisplayContext staticKeyContext;

    /** The StaticKey of this item, built once per stack object (hashing a big component patch every frame would cost). */
    public MeshCache.StaticKey staticKey(ItemStack stack, ItemModel model, ItemDisplayContext context) {
        if (this.staticKey == null || this.staticKeyStack != stack || this.staticKeyModel != model || this.staticKeyContext != context) {
            this.staticKey = new MeshCache.StaticKey(stack, model, context);
            this.staticKeyStack = stack;
            this.staticKeyModel = model;
            this.staticKeyContext = context;
        }
        return this.staticKey;
    }

    /** The same (or an equal) item as last time, and nothing but the item decides what it looks like. */
    public boolean sameItem(ItemStack stack, ItemDisplayContext context, @Nullable ItemModel model, int generation) {
        if (this.stack == null || !this.staticModel || this.context != context || this.model != model || this.generation != generation) {
            return false;
        }
        if (this.stack == stack) {
            return true;
        }
        // the server re-sending a display's item data gives it a new but equal ItemStack: compare the components
        // (cheap) before resolving a composite of thousands of models again
        if (ItemStack.matches(this.stack, stack)) {
            this.stack = stack;
            return true;
        }
        return false;
    }

    public void set(ItemStack stack, ItemDisplayContext context, @Nullable ItemModel model, int generation, @Nullable MeshKey key,
                    boolean staticModel, int[][] objmcLayerTints) {
        this.stack = stack;
        this.context = context;
        this.model = model;
        this.generation = generation;
        this.key = key;
        this.entry = null;
        this.staticModel = staticModel;
        this.objmcLayerTints = objmcLayerTints;
    }

    /** objmc part tints for this entry (by its parts' layers). */
    public int[] objmcTints(MeshEntry entry) {
        if (entry.objmcParts.isEmpty()) {
            return NO_TINTS;
        }
        int[] out = new int[entry.objmcParts.size()];
        for (int i = 0; i < out.length; i++) {
            MeshEntry.ObjmcPart part = entry.objmcParts.get(i);
            int[] t = part.layerIndex() < this.objmcLayerTints.length ? this.objmcLayerTints[part.layerIndex()] : null;
            out[i] = t != null && part.tintIndex() >= 0 && part.tintIndex() < t.length ? t[part.tintIndex()] : -1;
        }
        return out;
    }
}
