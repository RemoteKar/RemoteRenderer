package kr.gcb.remoterenderer.mesh;

import java.util.Arrays;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.jspecify.annotations.Nullable;

/**
 * What a resolved item looks like, layer by layer: the baked quads (by identity - they belong to the loaded
 * model), the transforms applied to them and the tint colours. Two displays with equal keys draw the same
 * geometry, so they share one baked mesh.
 */
public final class MeshKey {
    final Layer[] layers;
    private final int hash;
    /** All quads of all layers: how heavy the vanilla renderer would find this item. */
    public final int quadCount;

    MeshKey(Layer[] layers) {
        this.layers = layers;
        this.hash = Arrays.hashCode(layers);
        int quads = 0;
        for (Layer l : layers) {
            quads += l.quads.all().size();
        }
        this.quadCount = quads;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof MeshKey k && k.hash == this.hash && Arrays.equals(k.layers, this.layers);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    /** One layer of an ItemStackRenderState. objmc layers keep tints == null: their tint is per instance data. */
    static final class Layer {
        final ItemQuads quads;
        final ItemTransform transform;
        final boolean leftHand;
        final float[] local;
        final int @Nullable [] tints;
        final boolean objmc;
        private final int hash;

        Layer(ItemQuads quads, ItemTransform transform, boolean leftHand, float[] local, int @Nullable [] tints, boolean objmc) {
            this.quads = quads;
            this.transform = transform;
            this.leftHand = leftHand;
            this.local = local;
            this.tints = tints;
            this.objmc = objmc;
            int h = System.identityHashCode(quads);
            h = 31 * h + System.identityHashCode(transform);
            h = 31 * h + (leftHand ? 1 : 0);
            h = 31 * h + Arrays.hashCode(local);
            h = 31 * h + Arrays.hashCode(tints);
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Layer l && l.hash == this.hash && l.quads == this.quads && l.transform == this.transform
                && l.leftHand == this.leftHand && l.objmc == this.objmc && Arrays.equals(l.local, this.local) && Arrays.equals(l.tints, this.tints);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
