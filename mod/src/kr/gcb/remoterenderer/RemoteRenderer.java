package kr.gcb.remoterenderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.lang.reflect.Method;
import java.nio.file.Path;
import kr.gcb.remoterenderer.duck.RrEntity;
import kr.gcb.remoterenderer.duck.RrRenderState;
import kr.gcb.remoterenderer.mesh.EntityCache;
import kr.gcb.remoterenderer.mesh.MeshCache;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.mesh.MeshKey;
import kr.gcb.remoterenderer.mixin.CompositeModelAccessor;
import kr.gcb.remoterenderer.mixin.CuboidItemModelWrapperAccessor;
import java.nio.ByteBuffer;
import kr.gcb.remoterenderer.render.MeshSubmit;
import kr.gcb.remoterenderer.render.PackShaders;
import kr.gcb.remoterenderer.render.RigSubmit;
import kr.gcb.remoterenderer.rig.RigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.color.item.Dye;
import net.minecraft.client.color.item.Firework;
import net.minecraft.client.color.item.GrassColorSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.Potion;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.EmptyModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The glue between the vanilla item display renderer and the GPU path.
 *
 * extract: decides per display whether its item takes the GPU path and remembers the baked model in the render
 * state; while the display keeps the same item, the item model is not resolved again (static model kinds).
 * submit: instead of handing every quad of every layer to the item feature renderer (which re-transforms every
 * vertex on the CPU each frame), one MeshSubmit carrying the pose goes to the solid phase; translucent layers
 * still go the vanilla way.
 */
public final class RemoteRenderer {
    public static final Logger LOG = LoggerFactory.getLogger("RemoteRenderer");
    public static RrConfig config = new RrConfig();
    public static final Stats STATS = new Stats();
    public static @Nullable Path configFile;

    private static final boolean DEBUG = Boolean.getBoolean("remoterenderer.debug");
    /** Open the settings screen at the next frame with a world (the command runs while the chat screen is open). */
    public static volatile boolean openMenu = Boolean.getBoolean("remoterenderer.openMenu");
    private static int debugLines;
    private static boolean disabled;
    private static @Nullable String disabledReason;

    private static boolean irisLookedUp;
    private static @Nullable Object irisApi;
    private static @Nullable Method irisInUse;
    private static long irisCheckedAt;
    private static boolean irisShaders;

    private RemoteRenderer() {
    }

    /** Is the GPU path on right now? */
    public static boolean active() {
        if (!config.enabled || disabled) {
            return false;
        }
        if (irisShaderPackInUse()) {
            // a shader pack draws entities with its own programs: models go through vanilla render types (compat)
            return config.shaderCompat;
        }
        return customItemShader == null || config.ignoreCustomItemShaders || PackShaders.active();
    }

    /**
     * An Iris shader pack is on and shaderCompat: the baked models and rigs are handed to vanilla render types (the pack's
     * programs, its shadow pass) instead of RemoteRenderer's own pipelines. The model resolution is still skipped.
     */
    public static boolean compat() {
        return config.shaderCompat && irisShaderPackInUse();
    }

    /** LightCoordsUtil.lightCoordsWithEmission: block and sky light raised to the quad's own emission. */
    public static int withEmission(int light, int emission) {
        if (emission <= 0) {
            return light;
        }
        int block = Math.max(light & 0xFFFF, emission << 4), sky = Math.max(light >>> 16 & 0xFFFF, emission << 4);
        return block | sky << 16;
    }

    /**
     * The files the vanilla item shader is made of. A resource pack that replaces one of them (a server's HUD drawn by
     * item displays in item.vsh, decals drawn in item.fsh, ...) expects every item display to go through its shader;
     * RemoteRenderer's own shader would silently drop those effects, so it steps aside while such a pack is loaded.
     */
    private static final String[] ITEM_SHADER_FILES = {"shaders/core/item.vsh", "shaders/core/item.fsh", "shaders/include/light.glsl",
        "shaders/include/fog.glsl", "shaders/include/dynamictransforms.glsl", "shaders/include/projection.glsl", "shaders/include/sample_lightmap.glsl"};
    private static @Nullable String customItemShader;

    /** Called when the shaders were (re)loaded: which pack, if any, replaces the item shader now. */
    public static void checkCustomItemShaders() {
        String found = null;
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            for (String file : ITEM_SHADER_FILES) {
                var resource = resources.getResource(Identifier.withDefaultNamespace(file));
                if (resource.isPresent() && !"vanilla".equals(resource.get().sourcePackId())) {
                    found = resource.get().sourcePackId() + " (" + file + ")";
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not check the item shader's resource packs: {}", e.toString());
        }
        if (!java.util.Objects.equals(found, customItemShader)) {
            if (found != null) {
                LOG.info("Resource pack {} replaces the item shader{}", found, config.ignoreCustomItemShaders ? " (ignored: ignoreCustomItemShaders=true)"
                    : config.adaptCustomItemShaders ? ": RemoteRenderer runs it instanced" : ": RemoteRenderer steps aside (adaptCustomItemShaders=false)");
            } else {
                LOG.info("Item shader is vanilla again: RemoteRenderer takes over");
            }
            MeshCache.requestClear();
        }
        customItemShader = found;
    }

    /** -Dremoterenderer.forceAdapt: run even the vanilla item shader through the adapted pipelines (tests). */
    private static final boolean FORCE_ADAPT = Boolean.getBoolean("remoterenderer.forceAdapt");

    /** After the shaders were (re)loaded: a pack's item shader is run instanced (PackShaders) instead of stepping aside. */
    public static void adaptItemShader() {
        boolean custom = customItemShader != null && config.adaptCustomItemShaders && !config.ignoreCustomItemShaders;
        PackShaders.rebuild(Minecraft.getInstance().getResourceManager(), custom, FORCE_ADAPT);
        MeshCache.requestClear();
    }

    public static @Nullable String customItemShader() {
        return customItemShader;
    }

    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            disabledReason = reason;
            LOG.error("RemoteRenderer turned itself off: {}", reason);
        }
    }

    public static @Nullable String disabledReason() {
        return disabledReason;
    }

    public static void setEnabled(boolean enabled) {
        if (config.enabled != enabled) {
            config.enabled = enabled;
            MeshCache.requestClear();
        }
        if (enabled) {
            disabled = false;
            disabledReason = null;
        }
    }

    public static void onModelsReloaded() {
        MeshCache.requestClear();
        try {
            kr.gcb.remoterenderer.rig.RigManager.reload(Minecraft.getInstance().getResourceManager());
        } catch (RuntimeException e) {
            LOG.error("Rigs not reloaded", e);
        }
    }

    /**
     * An Iris shader pack draws entities into its own G-buffers with its own programs; our pipeline knows
     * nothing about those, so with a shader pack on everything goes the vanilla way (Iris then handles it).
     */
    private static boolean irisShaderPackInUse() {
        long now = System.nanoTime();
        if (now - irisCheckedAt < 250_000_000L) {
            return irisShaders;
        }
        irisCheckedAt = now;
        if (!irisLookedUp) {
            irisLookedUp = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                irisApi = null;
            }
        }
        boolean inUse = false;
        if (irisApi != null && irisInUse != null) {
            try {
                inUse = (Boolean) irisInUse.invoke(irisApi);
            } catch (ReflectiveOperationException | RuntimeException e) {
                inUse = false;
            }
        }
        if (inUse != irisShaders) {
            LOG.info("Iris shader pack {}: RemoteRenderer {}", inUse ? "on" : "off", !inUse ? "draws with its own pipelines again"
                : config.shaderCompat ? "hands its models and rigs to the pack's programs (shaderCompat)" : "steps aside (shaderCompat=false)");
            MeshCache.requestClear();
        }
        irisShaders = inUse;
        return inUse;
    }

    // ---------------------------------------------------------------- extract

    /**
     * Replaces ItemModelResolver.updateForNonLiving in ItemDisplayRenderer.extractRenderState.
     *
     * 1. the display shows the same (or an equal) item as last time and its look depends on the item alone: no
     *    resolution at all, the baked model is drawn;
     * 2. another display already showed an equal item: take what was found for it (a new BetterModel entity costs
     *    nothing, however many sub-models its bones have);
     * 3. otherwise the item is resolved the vanilla way (recording the path it takes), keyed, and looked up; a new
     *    model is baked in the background and drawn the vanilla way until it is ready.
     */
    public static void extract(ItemModelResolver resolver, ItemStackRenderState out, ItemStack stack, ItemDisplayContext context, Entity entity,
                               ItemDisplayEntityRenderState state, Operation<Void> original) {
        RrRenderState dm = (RrRenderState) state;
        dm.remoterenderer$setRig(null, null, -1, null, false);
        if (!active() || state.outlineColor != 0 || !(entity instanceof RrEntity holder)) {
            dm.remoterenderer$set(null, null);
            original.call(resolver, out, stack, context, entity);
            return;
        }
        EntityCache cache = holder.remoterenderer$cache();
        cache.entity = entity;
        if (config.rigs) {
            RigManager.BoneRef bone = RigManager.boneOf(stack);
            if (bone != null && bone.rig().state() != RigManager.State.FAILED) {
                Entity vehicle = entity.getVehicle();
                dm.remoterenderer$setRig(bone, vehicle != null ? vehicle : entity, RigManager.tintOf(stack), RigManager.clipOf(stack), RigManager.physicsOff(stack));
                if (!bone.rig().def.boneGeometry() && bone.rig().def.hideBones) {
                    // the rig brings its own mesh: this bone's item model is never resolved (a BetterModel mesh bone is a
                    // composite of thousands of models); while the rig is still loading, nothing is drawn
                    dm.remoterenderer$set(null, null);
                    if (bone.rig().state() != RigManager.State.READY) {
                        dm.remoterenderer$setRig(null, null, -1, null, false);
                    }
                    STATS.rigBones++;
                    return;
                }
            }
        }
        ItemModel model = currentModel(stack);
        int gen = MeshCache.generation;
        boolean known = config.cacheResolve && cache.sameItem(stack, context, model, gen);
        MeshCache.StaticKey staticKey = null;
        if (!known && config.cacheResolve && model != null) {
            staticKey = cache.staticKey(stack, model, context);
            MeshCache.StaticInfo info = MeshCache.getStatic(staticKey);
            if (info != null) {
                cache.set(stack, context, model, gen, info.key(), true, info.objmcLayerTints());
                known = true;
                STATS.staticHits++;
            } else {
                int quads = MeshCache.plainQuads(model);
                if (quads >= 0) {
                    // first sight of a plain item: resolved, keyed and baked on a background worker
                    MeshCache.startStaticJob(staticKey, stack, context, resolver, quads);
                    dm.remoterenderer$set(null, null);
                    if (MeshCache.staticJobHidden(staticKey)) {
                        STATS.hiddenWhileBaking++;
                        return;
                    }
                    long t0 = System.nanoTime();
                    original.call(resolver, out, stack, context, entity);
                    STATS.frameResolveNanos += System.nanoTime() - t0;
                    return;
                }
            }
        }
        if (known) {
            MeshEntry entry = cache.key == null ? null : cache.entry;
            if (cache.key != null && (entry == null || entry.isRetired())) {
                entry = MeshCache.getOrBake(cache.key, context);
                cache.entry = entry;
            }
            if (entry != null) {
                STATS.cachedResolves++;
                dm.remoterenderer$set(entry, cache);
                return;
            }
            dm.remoterenderer$set(null, null);
            if (cache.key != null && MeshCache.hideWhileBaking(cache.key)) {
                // a heavy model still baking: not drawn for these few frames (out stays empty)
                STATS.hiddenWhileBaking++;
                return;
            }
            // unsupported, or still baking a light model: the vanilla renderer draws it this frame
            long t0 = System.nanoTime();
            original.call(resolver, out, stack, context, entity);
            STATS.frameResolveNanos += System.nanoTime() - t0;
            return;
        }
        long t0 = System.nanoTime();
        ResolvePath.begin();
        try {
            original.call(resolver, out, stack, context, entity);
        } finally {
            ResolvePath.end();
        }
        long t1 = System.nanoTime();
        STATS.frameResolveNanos += t1 - t0;
        STATS.resolves++;
        boolean stackOnly = model != null && ResolvePath.stackOnly();
        MeshCache.Resolved resolved = MeshCache.keyOf(out);
        MeshKey key = resolved == null ? null : resolved.key();
        int[][] objmcTints = resolved == null ? MeshCache.NO_LAYER_TINTS : resolved.objmcLayerTints();
        cache.set(stack, context, model, gen, key, stackOnly, objmcTints);
        if (stackOnly) {
            MeshCache.putStatic(staticKey != null ? staticKey : cache.staticKey(stack, model, context), key, objmcTints);
        }
        MeshEntry entry = key == null ? null : MeshCache.getOrBake(key, context);
        cache.entry = entry;
        STATS.frameKeyNanos += System.nanoTime() - t1;
        if (entry == null && key != null && MeshCache.hideWhileBaking(key)) {
            out.clear();
            STATS.hiddenWhileBaking++;
        }
        // entry null: out holds the resolved item (unless hidden), the vanilla renderer draws it
        dm.remoterenderer$set(entry, entry == null ? null : cache);
    }

    private static @Nullable ItemModel currentModel(ItemStack stack) {
        Identifier id = stack.get(DataComponents.ITEM_MODEL);
        return id == null ? null : Minecraft.getInstance().getModelManager().getItemModel(id);
    }

    // ---------------------------------------------------------------- submit

    /**
     * Replaces the body of ItemDisplayRenderer.submitInner when the state carries a baked model.
     * Returns false when the vanilla body has to run (the item is then resolved now if extract skipped it).
     */
    public static boolean submit(ItemDisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords) {
        RrRenderState dm = (RrRenderState) state;
        MeshEntry entry = dm.remoterenderer$entry();
        RigManager.BoneRef rig = dm.remoterenderer$rig();
        if (DUMP != null) {
            dump(state, dm, poseStack, entry);
        }
        SubmitNodeCollection target = collector instanceof SubmitNodeStorage storage ? storage.order(0)
            : collector instanceof SubmitNodeCollection collection ? collection : null;
        if (target == null) {
            return false;
        }
        if (compat()) {
            return submitCompat(dm, poseStack, collector, lightCoords, entry, rig);
        }
        if (rig != null && dm.remoterenderer$rigInstance() != null) {
            Object instance = dm.remoterenderer$rigInstance();
            if (rig.rig().def.boneGeometry()) {
                // the bone's own baked model becomes part of the skinned mesh
                if (entry != null && !entry.isRetired()) {
                    entry.retainCpu = true;
                    if (entry.cpuVertices() != null) {
                        target.solid.submit(RigSubmit.obtain(rig, instance, poseStack.last().pose(), lightCoords, dm.remoterenderer$rigTint(), entry,
                            dm.remoterenderer$rigClip(), dm.remoterenderer$rigPhysicsOff()));
                        return true;
                    }
                }
            } else {
                target.solid.submit(RigSubmit.obtain(rig, instance, poseStack.last().pose(), lightCoords, dm.remoterenderer$rigTint(), null,
                    dm.remoterenderer$rigClip(), dm.remoterenderer$rigPhysicsOff()));
                if (rig.rig().def.hideBones || entry == null) {
                    return true;
                }
            }
        }
        if (entry == null || entry.isRetired()) {
            return false;
        }
        // as ItemDisplayRenderer.submitInner does before ItemStackRenderState.submit
        poseStack.rotate(Axis.YP, (float) Math.PI);
        PoseStack.Pose pose = poseStack.last();
        // not with a pack's own item shader: such shaders may draw a display somewhere else than where it stands (a HUD
        // kept out of sight and moved onto the screen by item.vsh), and vanilla draws every display anyway
        if (config.cullOffscreen && customItemShader == null && !compat() && entry.objmcParts.isEmpty() && entry.translucent.isEmpty() && entry.solidQuads > 0
            && offscreen(pose.pose(), entry.bounds)) {
            // vanilla draws display entities even off screen (Display.noCulling); the box of the baked model is outside the view
            STATS.frameMeshCulled++;
            return true;
        }
        EntityCache cache = dm.remoterenderer$cache();
        if (entry.hasGpuWork()) {
            int[] objmcTints = cache != null ? cache.objmcTints(entry) : EntityCache.NO_TINTS;
            target.solid.submit(MeshSubmit.obtain(entry, pose.pose(), pose.normal(), lightCoords, OverlayTexture.NO_OVERLAY, objmcTints));
        }
        for (MeshEntry.TranslucentLayer layer : entry.translucent) {
            poseStack.pushPose();
            poseStack.last().mulPose(layer.pose());
            collector.submitItem(poseStack, entry.context, lightCoords, OverlayTexture.NO_OVERLAY, 0, layer.tints(), layer.quads(),
                ItemStackRenderState.FoilType.NONE);
            poseStack.popPose();
        }
        return true;
    }

    /** The vanilla path after all, for a state whose item extract did not resolve. */
    /**
     * Shader-pack mode: the baked model's quads are written into vanilla's buffers for its render types (one custom
     * geometry per part instead of one item submit per layer - thousands for a BetterModel mesh bone), so the shader
     * pack draws them with its own programs and puts them into its shadow map too. Rigs are skinned on the CPU.
     */
    private static boolean submitCompat(RrRenderState dm, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, @Nullable MeshEntry entry,
                                        RigManager.@Nullable BoneRef rig) {
        if (rig != null && dm.remoterenderer$rigInstance() != null && (!rig.rig().def.boneGeometry() || entry != null && entry.cpuVertices() != null)) {
            if (kr.gcb.remoterenderer.render.RigRenderer.compatSubmit(rig, dm.remoterenderer$rigInstance(), poseStack.last().pose(), lightCoords,
                dm.remoterenderer$rigTint(), entry, dm.remoterenderer$rigClip(), dm.remoterenderer$rigPhysicsOff(), collector)) {
                return true;
            }
        }
        if (entry == null || entry.isRetired() || !entry.objmcParts.isEmpty()) {
            return false;
        }
        ByteBuffer data = entry.cpuVertices();
        if (data == null) {
            return false;
        }
        poseStack.rotate(Axis.YP, (float) Math.PI);
        for (MeshEntry.Part part : entry.parts) {
            collector.submitCustomGeometry(poseStack, part.renderType(), (pose, consumer) -> writeQuads(data, part, pose, consumer, lightCoords));
        }
        for (MeshEntry.TranslucentLayer layer : entry.translucent) {
            poseStack.pushPose();
            poseStack.last().mulPose(layer.pose());
            collector.submitItem(poseStack, entry.context, lightCoords, OverlayTexture.NO_OVERLAY, 0, layer.tints(), layer.quads(),
                ItemStackRenderState.FoilType.NONE);
            poseStack.popPose();
        }
        STATS.frameInstances++;
        return true;
    }

    private static final org.joml.Vector3f WRITE_P = new org.joml.Vector3f(), WRITE_N = new org.joml.Vector3f();

    /** A part's baked quads (32-byte vertices, model space) through the pose into a vanilla vertex consumer. */
    private static void writeQuads(ByteBuffer d, MeshEntry.Part part, PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer out, int light) {
        org.joml.Matrix4f m = pose.pose();
        org.joml.Matrix3f n = pose.normal();
        for (int i = 0; i < part.quadCount() * 4; i++) {
            int at = (part.firstVertex() + i) * 32;
            m.transformPosition(d.getFloat(at), d.getFloat(at + 4), d.getFloat(at + 8), WRITE_P);
            n.transform(d.get(at + 24) / 127.0F, d.get(at + 25) / 127.0F, d.get(at + 26) / 127.0F, WRITE_N);
            if (WRITE_N.lengthSquared() > 1.0e-12F) {
                WRITE_N.normalize();
            }
            int color = (d.get(at + 15) & 255) << 24 | (d.get(at + 12) & 255) << 16 | (d.get(at + 13) & 255) << 8 | d.get(at + 14) & 255;
            out.addVertex(WRITE_P.x, WRITE_P.y, WRITE_P.z, color, d.getFloat(at + 16), d.getFloat(at + 20), OverlayTexture.NO_OVERLAY,
                withEmission(light, d.get(at + 28) & 255), WRITE_N.x, WRITE_N.y, WRITE_N.z);
        }
        STATS.frameQuads += part.quadCount();
    }

    private static final org.joml.Vector3f CULL_CENTER = new org.joml.Vector3f();

    /** The baked model's bounding sphere, moved by the pose (camera relative), against the view frustum. */
    private static boolean offscreen(org.joml.Matrix4f pose, float[] b) {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        var frustum = camera.getCullFrustum();
        if (frustum == null) {
            return false;
        }
        float cx = (b[0] + b[3]) * 0.5F, cy = (b[1] + b[4]) * 0.5F, cz = (b[2] + b[5]) * 0.5F;
        float hx = b[3] - cx, hy = b[4] - cy, hz = b[5] - cz;
        float r = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        pose.transformPosition(cx, cy, cz, CULL_CENTER);
        float s = r * (float) Math.sqrt(Math.max(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02(),
            Math.max(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12(), pose.m20() * pose.m20() + pose.m21() * pose.m21() + pose.m22() * pose.m22())));
        var cam = camera.position();
        double x = CULL_CENTER.x + cam.x, y = CULL_CENTER.y + cam.y, z = CULL_CENTER.z + cam.z;
        return !frustum.isVisible(new net.minecraft.world.phys.AABB(x - s, y - s, z - s, x + s, y + s, z + s));
    }

    /** -Dremoterenderer.dump=<part of an item model id>: logs each such display's transformation and bounds once. */
    private static final @Nullable String DUMP = System.getProperty("remoterenderer.dump");
    private static final java.util.Set<String> DUMPED = new java.util.HashSet<>();

    private static void dump(ItemDisplayEntityRenderState state, RrRenderState dm, PoseStack poseStack, @Nullable MeshEntry entry) {
        EntityCache cache = dm.remoterenderer$cache();
        ItemStack stack = cache != null ? cache.stack : null;
        Identifier model = stack == null ? null : stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !model.toString().contains(DUMP) || entry == null) {
            return;
        }
        String key = model + "@" + System.identityHashCode(cache);
        if (!DUMPED.add(key)) {
            return;
        }
        var cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
        org.joml.Matrix4f t = new org.joml.Matrix4f().translation((float) (cam.x - state.x), (float) (cam.y - state.y), (float) (cam.z - state.z))
            .mul(poseStack.last().pose());
        float[] b = entry.bounds;
        LOG.info(String.format(java.util.Locale.ROOT,
            "RR_DUMP %s entity=%.3f,%.3f,%.3f transform(rowmajor)=[%.6f,%.6f,%.6f,%.6f, %.6f,%.6f,%.6f,%.6f, %.6f,%.6f,%.6f,%.6f] baked=[%.5f,%.5f,%.5f .. %.5f,%.5f,%.5f] quads=%d",
            model, state.x, state.y, state.z, t.m00(), t.m10(), t.m20(), t.m30(), t.m01(), t.m11(), t.m21(), t.m31(), t.m02(), t.m12(), t.m22(), t.m32(),
            b[0], b[1], b[2], b[3], b[4], b[5], entry.solidQuads));
    }

    public static void resolveLate(ItemModelResolver resolver, ItemDisplayEntityRenderState state) {
        RrRenderState dm = (RrRenderState) state;
        EntityCache cache = dm.remoterenderer$cache();
        if (state.item.isEmpty() && cache != null && cache.stack != null && cache.context != null && cache.entity != null) {
            resolver.updateForNonLiving(state.item, cache.stack, cache.context, cache.entity);
            STATS.lateFallbacks++;
        }
        dm.remoterenderer$set(null, null);
    }
}
