package kr.gcb.remoterenderer.mesh;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.mixin.ItemStackRenderStateAccessor;
import kr.gcb.remoterenderer.mixin.LayerRenderStateAccessor;
import kr.gcb.remoterenderer.objmc.ObjmcDecoder;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * All baked models, shared by every display that looks the same. Render thread only, except the bakes themselves,
 * which run on the game's background workers: a new model is drawn the vanilla way for the few frames until its bake
 * is done, so its first appearance never stalls a frame.
 */
public final class MeshCache {
    private static final Map<MeshKey, MeshEntry> ENTRIES = new HashMap<>();
    private static final Map<MeshKey, Pending> PENDING = new HashMap<>();
    private static final Set<MeshKey> UNSUPPORTED = new HashSet<>();
    /** item + model + context + components -> what the resolution of such a stack came to (only for stack-only models) */
    private static final Map<StaticKey, StaticInfo> STATIC = new HashMap<>();
    /** Bumped when everything is thrown away (resource reload, toggling): per-entity caches compare against it. */
    public static int generation;
    private static boolean clearRequested;
    private static long residentBytes;
    private static long lastEvictionCheck;

    public static final int[][] NO_LAYER_TINTS = new int[0][];

    private MeshCache() {
    }

    /**
     * Bakes run on a few low-priority daemon threads of their own (bakeThreads, default 2) rather than on the game's
     * worker pool: a burst of new models must not take the cores the render thread and chunk building need.
     */
    private static final java.util.concurrent.ExecutorService BAKERS = java.util.concurrent.Executors.newFixedThreadPool(
        Math.max(1, RemoteRenderer.config.bakeThreads), r -> {
            Thread t = new Thread(r, "RemoteRenderer-Bake");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    private record Pending(CompletableFuture<MeshEntry> future, int generation, long started) {
    }

    /**
     * While a heavy model (hideWhileBakingQuads quads or more) is still being baked, for at most hideWhileBakingMs,
     * it is not drawn at all: drawing it the vanilla way for those frames costs exactly the stall the background bake
     * avoids. It appears one to three frames later instead.
     */
    public static boolean hideWhileBaking(MeshKey key) {
        if (key.quadCount < RemoteRenderer.config.hideWhileBakingQuads) {
            return false;
        }
        Pending p = PENDING.get(key);
        return p != null && System.nanoTime() - p.started < RemoteRenderer.config.hideWhileBakingMs * 1_000_000L;
    }

    /** item + model + context + components; the hash is computed once (a player-skin limb carries hundreds of colours). */
    public static final class StaticKey {
        final Item item;
        final ItemModel model;
        final ItemDisplayContext context;
        final DataComponentPatch patch;
        private final int hash;

        public StaticKey(ItemStack stack, ItemModel model, ItemDisplayContext context) {
            this.item = stack.getItem();
            this.model = model;
            this.context = context;
            this.patch = stack.getComponentsPatch();
            this.hash = ((System.identityHashCode(this.item) * 31 + System.identityHashCode(model)) * 31 + context.hashCode()) * 31 + this.patch.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof StaticKey k && k.hash == this.hash && k.item == this.item && k.model == this.model && k.context == this.context
                && k.patch.equals(this.patch);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /** A first-sight item resolved, keyed and baked on a background worker. */
    private record StaticJob(CompletableFuture<StaticResult> future, int generation, long started, int quads) {
    }

    private record StaticResult(@Nullable MeshKey key, int[][] objmcLayerTints, @Nullable MeshEntry entry) {
    }

    private static final Map<StaticKey, StaticJob> STATIC_JOBS = new HashMap<>();
    /** ItemModel -> its quads if it is a plain tree (composites of "model"s with stack-only tints), else -1 */
    private static final Map<ItemModel, Integer> PLAIN = new java.util.IdentityHashMap<>();

    /** key == null: such a stack cannot take the GPU path. */
    public record StaticInfo(@Nullable MeshKey key, int[][] objmcLayerTints) {
    }

    /** A resolved item: its key and, for objmc layers, their tints (objmc reads controls from the tint). */
    public record Resolved(MeshKey key, int[][] objmcLayerTints) {
    }

    /** The key of a resolved item; null if something in it cannot take the GPU path (special renderer, glint, empty). */
    public static @Nullable Resolved keyOf(ItemStackRenderState state) {
        ItemStackRenderStateAccessor s = (ItemStackRenderStateAccessor) state;
        int count = s.remoterenderer$activeLayerCount();
        if (count == 0) {
            return null;
        }
        ItemStackRenderState.LayerRenderState[] layers = s.remoterenderer$layers();
        ItemDisplayContext context = s.remoterenderer$displayContext();
        boolean leftHand = context.leftHand();
        boolean objmcEnabled = RemoteRenderer.config.objmc;
        MeshKey.Layer[] keyLayers = new MeshKey.Layer[count];
        int[][] objmcTints = null;
        for (int i = 0; i < count; i++) {
            LayerRenderStateAccessor l = (LayerRenderStateAccessor) (Object) layers[i];
            if (l.remoterenderer$specialRenderer() != null || l.remoterenderer$foilType() != ItemStackRenderState.FoilType.NONE) {
                return null;
            }
            ItemQuads quads = l.remoterenderer$quads();
            IntList tl = l.remoterenderer$tintLayers();
            int[] tints = tl == null || tl.isEmpty() ? EntityCache.NO_TINTS : tl.toIntArray();
            boolean objmc = objmcEnabled && isObjmcLayer(quads);
            if (objmc) {
                if (objmcTints == null) {
                    objmcTints = new int[count][];
                }
                objmcTints[i] = tints;
            }
            float[] local = l.remoterenderer$localTransform().get(new float[16]);
            keyLayers[i] = new MeshKey.Layer(quads, l.remoterenderer$itemTransform(), leftHand, local, objmc ? null : tints, objmc);
        }
        return new Resolved(new MeshKey(keyLayers), objmcTints == null ? NO_LAYER_TINTS : objmcTints);
    }

    private static boolean isObjmcLayer(ItemQuads quads) {
        List<BakedQuad> all = quads.all();
        return !all.isEmpty() && ObjmcDecoder.isObjmc(all.getFirst().materialInfo().sprite());
    }

    /** The baked model, or null while it is being baked (then the bake is started) or when it cannot be drawn. */
    public static @Nullable MeshEntry getOrBake(MeshKey key, ItemDisplayContext context) {
        MeshEntry entry = ENTRIES.get(key);
        if (entry != null) {
            return entry.objmcParts.isEmpty() && entry.solidQuads < RemoteRenderer.config.minQuads ? null : entry;
        }
        if (UNSUPPORTED.contains(key) || PENDING.containsKey(key)) {
            return null;
        }
        int gen = generation;
        CompletableFuture<MeshEntry> future = CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            MeshEntry baked = MeshBaker.bake(key, context);
            if (baked != null) {
                baked.bakeNanos = System.nanoTime() - t0;
            }
            return baked;
        }, BAKERS);
        PENDING.put(key, new Pending(future, gen, System.nanoTime()));
        RemoteRenderer.STATS.bakesStarted++;
        return null;
    }

    public static boolean isUnsupported(MeshKey key) {
        return UNSUPPORTED.contains(key);
    }

    public static @Nullable StaticInfo getStatic(StaticKey key) {
        return STATIC.isEmpty() ? null : STATIC.get(key);
    }

    public static void putStatic(StaticKey staticKey, @Nullable MeshKey key, int[][] objmcLayerTints) {
        if (STATIC.size() > 50_000) {
            STATIC.clear();
        }
        STATIC.put(staticKey, new StaticInfo(key, objmcLayerTints));
    }

    /**
     * The quads of a plain item model tree - composites of "model"s whose tints come from the stack - or -1.
     * Such a tree resolves the same on any thread, so a first-sight item of this kind is resolved, keyed and baked on a
     * background worker: the render thread does nothing heavy when a new BetterModel bone, mesh bone or objmc model
     * comes into view.
     */
    public static int plainQuads(ItemModel model) {
        Integer known = PLAIN.get(model);
        if (known == null) {
            known = plain(model, 0);
            PLAIN.put(model, known);
        }
        return known;
    }

    private static int plain(ItemModel model, int depth) {
        if (depth > 16) {
            return -1;
        }
        if (model instanceof net.minecraft.client.renderer.item.CuboidItemModelWrapper cuboid) {
            for (net.minecraft.client.color.item.ItemTintSource tint : ((kr.gcb.remoterenderer.mixin.CuboidItemModelWrapperAccessor) cuboid).remoterenderer$tints()) {
                if (!(tint instanceof net.minecraft.client.color.item.Constant || tint instanceof net.minecraft.client.color.item.CustomModelDataSource
                    || tint instanceof net.minecraft.client.color.item.Dye || tint instanceof net.minecraft.client.color.item.Potion
                    || tint instanceof net.minecraft.client.color.item.Firework || tint instanceof net.minecraft.client.color.item.GrassColorSource)) {
                    return -1;
                }
            }
            return ((kr.gcb.remoterenderer.mixin.CuboidItemModelWrapperAccessor) cuboid).remoterenderer$itemQuads().all().size();
        }
        if (model instanceof net.minecraft.client.renderer.item.CompositeModel composite) {
            int sum = 0;
            for (ItemModel child : ((kr.gcb.remoterenderer.mixin.CompositeModelAccessor) composite).remoterenderer$models()) {
                int q = plain(child, depth + 1);
                if (q < 0) {
                    return -1;
                }
                sum += q;
            }
            return sum;
        }
        if (model instanceof net.minecraft.client.renderer.item.EmptyModel || model instanceof net.minecraft.client.renderer.item.MissingItemModel) {
            return 0;
        }
        return -1;
    }

    /** Resolve + key + bake a first-sight item on a background worker (once per equal item). */
    public static void startStaticJob(StaticKey staticKey, ItemStack stack, ItemDisplayContext context, ItemModelResolver resolver, int quads) {
        if (STATIC.containsKey(staticKey) || STATIC_JOBS.containsKey(staticKey)) {
            return;
        }
        int gen = generation;
        ItemStack copy = stack.copy();
        CompletableFuture<StaticResult> future = CompletableFuture.supplyAsync(() -> {
            ItemStackRenderState state = new ItemStackRenderState();
            resolver.updateForTopItem(state, copy, context, null, null, 0);
            Resolved resolved = keyOf(state);
            if (resolved == null) {
                return new StaticResult(null, NO_LAYER_TINTS, null);
            }
            long t0 = System.nanoTime();
            MeshEntry baked = MeshBaker.bake(resolved.key(), context);
            if (baked != null) {
                baked.bakeNanos = System.nanoTime() - t0;
            }
            return new StaticResult(resolved.key(), resolved.objmcLayerTints(), baked);
        }, BAKERS);
        STATIC_JOBS.put(staticKey, new StaticJob(future, gen, System.nanoTime(), quads));
        RemoteRenderer.STATS.bakesStarted++;
    }

    /** A heavy first-sight item still in the works: not drawn yet (at most hideWhileBakingMs). */
    public static boolean staticJobHidden(StaticKey staticKey) {
        StaticJob job = STATIC_JOBS.get(staticKey);
        return job != null && job.quads >= RemoteRenderer.config.hideWhileBakingQuads
            && System.nanoTime() - job.started < RemoteRenderer.config.hideWhileBakingMs * 1_000_000L;
    }

    /** Once a frame, render thread: finished bakes become drawable. */
    public static void poll() {
        if (!STATIC_JOBS.isEmpty()) {
            pollStaticJobs();
        }
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<MeshKey, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MeshKey, Pending> e = it.next();
            Pending p = e.getValue();
            if (!p.future.isDone()) {
                continue;
            }
            it.remove();
            MeshEntry entry;
            try {
                entry = p.future.join();
            } catch (RuntimeException ex) {
                RemoteRenderer.LOG.warn("RemoteRenderer bake failed: {}", ex.toString());
                entry = null;
            }
            if (p.generation != generation) {
                if (entry != null) {
                    entry.retire();
                }
                continue;
            }
            if (entry == null || !entry.hasGpuWork()) {
                if (entry != null) {
                    entry.retire();
                }
                if (UNSUPPORTED.size() > 20_000) {
                    UNSUPPORTED.clear();
                }
                UNSUPPORTED.add(e.getKey());
                RemoteRenderer.STATS.unsupported++;
                continue;
            }
            entry.lastUsedNanos = System.nanoTime();
            ENTRIES.put(e.getKey(), entry);
            residentBytes += entry.vertexBytes;
            RemoteRenderer.STATS.baked++;
            RemoteRenderer.STATS.bakeNanos += entry.bakeNanos;
            RemoteRenderer.STATS.maxBakeNanos = Math.max(RemoteRenderer.STATS.maxBakeNanos, entry.bakeNanos);
        }
    }

    private static void pollStaticJobs() {
        Iterator<Map.Entry<StaticKey, StaticJob>> it = STATIC_JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StaticKey, StaticJob> e = it.next();
            StaticJob job = e.getValue();
            if (!job.future.isDone()) {
                continue;
            }
            it.remove();
            StaticResult result;
            try {
                result = job.future.join();
            } catch (RuntimeException ex) {
                RemoteRenderer.LOG.warn("RemoteRenderer background resolve failed: {}", ex.toString());
                continue;
            }
            MeshEntry entry = result.entry();
            if (job.generation != generation) {
                if (entry != null) {
                    entry.retire();
                }
                continue;
            }
            STATIC.put(e.getKey(), new StaticInfo(result.key(), result.objmcLayerTints()));
            if (result.key() == null) {
                continue;
            }
            if (entry == null || !entry.hasGpuWork()) {
                if (entry != null) {
                    entry.retire();
                }
                UNSUPPORTED.add(result.key());
                RemoteRenderer.STATS.unsupported++;
                continue;
            }
            if (ENTRIES.containsKey(result.key())) {
                entry.retire();
                continue;
            }
            entry.lastUsedNanos = System.nanoTime();
            ENTRIES.put(result.key(), entry);
            residentBytes += entry.vertexBytes;
            RemoteRenderer.STATS.baked++;
            RemoteRenderer.STATS.bakeNanos += entry.bakeNanos;
            RemoteRenderer.STATS.maxBakeNanos = Math.max(RemoteRenderer.STATS.maxBakeNanos, entry.bakeNanos);
        }
    }

    public static int pending() {
        return PENDING.size() + STATIC_JOBS.size();
    }

    /** Throw everything away at the end of this frame (resource reload, config change). */
    public static void requestClear() {
        clearRequested = true;
        generation++;
    }

    /** End of a feature frame (after the last draw): requested clears, and every 2 s the eviction of unused models. */
    public static void endFrame(long now, int evictSeconds) {
        if (clearRequested) {
            clearRequested = false;
            for (MeshEntry e : ENTRIES.values()) {
                e.retire();
            }
            ENTRIES.clear();
            UNSUPPORTED.clear();
            STATIC.clear();
            PLAIN.clear();
            ObjmcDecoder.clear();
            residentBytes = 0;
            // bakes still running belong to the old generation: poll() retires them when they finish
            return;
        }
        if (now - lastEvictionCheck < 2_000_000_000L) {
            return;
        }
        lastEvictionCheck = now;
        long limit = evictSeconds * 1_000_000_000L;
        Iterator<MeshEntry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            MeshEntry e = it.next();
            if (now - e.lastUsedNanos > limit) {
                residentBytes -= e.vertexBytes;
                e.retire();
                it.remove();
                RemoteRenderer.STATS.evicted++;
            }
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static long residentBytes() {
        return residentBytes;
    }
}
