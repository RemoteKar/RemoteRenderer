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

    /**
     * Fast entity path: how far from the entity the drawn model reached last time it was drawn as a plain model (its
     * sphere's distance + radius; -1: not culled early), and the display's render state then (a new one = new data).
     */
    public float cullReach = -1.0F;
    public @Nullable Object cullRenderState;

    private @Nullable ItemStack modelStack;
    private @Nullable ItemModel modelOfStack;
    private int modelGeneration = -1;

    // -Dremoterenderer.traceRotation: this display's drawn rotation in the last frame (render thread)
    private static final org.joml.Quaternionf TRACE_Q = new org.joml.Quaternionf();
    private float traceX, traceY, traceZ, traceW;
    private boolean traceValid;
    // the axis of the last turn bigger than half a degree (for turning back = wobble)
    private float traceAx, traceAy, traceAz;
    private boolean traceAxisValid;

    /** Test aid: how far the drawn display turned since the last frame goes to the stats (a still camera assumed). */
    public void traceRotation(org.joml.Matrix4fc pose) {
        org.joml.Quaternionf q = pose.getNormalizedRotation(TRACE_Q);
        if (this.traceValid) {
            double dot = Math.abs((double) q.x * this.traceX + (double) q.y * this.traceY + (double) q.z * this.traceZ + (double) q.w * this.traceW);
            double deg = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
            boolean reversed = false;
            if (deg > 0.5) {
                // the turn since the last frame: conj(previous) * current, axis = its vector part (sign fixed to the short way)
                float dw = this.traceW * q.w + this.traceX * q.x + this.traceY * q.y + this.traceZ * q.z;
                float dx = this.traceW * q.x - this.traceX * q.w - this.traceY * q.z + this.traceZ * q.y;
                float dy = this.traceW * q.y + this.traceX * q.z - this.traceY * q.w - this.traceZ * q.x;
                float dz = this.traceW * q.z - this.traceX * q.y + this.traceY * q.x - this.traceZ * q.w;
                float s = dw < 0.0F ? -1.0F : 1.0F;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 1.0e-6F) {
                    float ax = dx * s / len, ay = dy * s / len, az = dz * s / len;
                    reversed = this.traceAxisValid && ax * this.traceAx + ay * this.traceAy + az * this.traceAz < -0.5F;
                    this.traceAx = ax;
                    this.traceAy = ay;
                    this.traceAz = az;
                    this.traceAxisValid = true;
                }
            }
            kr.gcb.remoterenderer.RemoteRenderer.STATS.traceRotation(deg, reversed);
        }
        this.traceX = q.x;
        this.traceY = q.y;
        this.traceZ = q.z;
        this.traceW = q.w;
        this.traceValid = true;
    }

    /** The item model the stack's item_model names, looked up again only for another stack object or after a reload. */
    public @Nullable ItemModel model(ItemStack stack, int generation) {
        if (this.modelStack != stack || this.modelGeneration != generation) {
            net.minecraft.resources.Identifier id = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
            this.modelOfStack = id == null ? null : net.minecraft.client.Minecraft.getInstance().getModelManager().getItemModel(id);
            this.modelStack = stack;
            this.modelGeneration = generation;
        }
        return this.modelOfStack;
    }

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
