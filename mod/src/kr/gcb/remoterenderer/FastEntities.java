package kr.gcb.remoterenderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import kr.gcb.remoterenderer.duck.RrEntity;
import kr.gcb.remoterenderer.duck.RrItemDisplayRenderer;
import kr.gcb.remoterenderer.duck.RrRenderState;
import kr.gcb.remoterenderer.mesh.EntityCache;
import kr.gcb.remoterenderer.mixin.FrustumAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The "fast entity path" (config fastEntities): the vanilla per-entity work around an item display that RemoteRenderer
 * draws anyway - thousands of BetterModel bone displays - done in one tight place.
 *
 * <ul>
 * <li>shouldRender: a display whose model was off screen is dropped before its render state is even extracted, by a
 *     sphere around the entity that covers any turn of the bone around it (vanilla never culls displays);</li>
 * <li>extractEntity: the render state is filled with what a plain item display needs (no name tag, scoreboard, leash,
 *     fire, shadow lookups; one light lookup per block per frame); anything unusual (a name, glowing, a shadow, riding
 *     a minecart, another renderer) takes the vanilla path;</li>
 * <li>submit: vanilla's pose math (same calls, same results) without the dispatcher, name display and pose stack copies.</li>
 * </ul>
 * Off with a shader pack (its shadow pass and entity ids go through vanilla's dispatcher).
 */
public final class FastEntities {
    private static final Long2IntOpenHashMap LIGHT = new Long2IntOpenHashMap();
    private static final PoseStack STACK = new PoseStack();
    private static final Quaternionf ORIENTATION = new Quaternionf();
    private static boolean on;
    private static boolean belowName;
    private static DisplayRenderer.@Nullable ItemDisplayRenderer renderer;
    private static boolean rendererLooked;

    private FastEntities() {
    }

    /** Once a frame, before extraction. */
    public static void beginFrame() {
        LIGHT.clear();
        renderer = null;
        rendererLooked = false;
        Minecraft mc = Minecraft.getInstance();
        on = RemoteRenderer.config.fastEntities && mc.level != null && RemoteRenderer.active() && !RemoteRenderer.compat();
        belowName = mc.level != null && mc.level.getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME) != null;
    }

    public static boolean on() {
        return on;
    }

    // ---------------------------------------------------------------- shouldRender

    /** True: the display is certainly off screen this frame (its model was, and nothing about it changed since). */
    public static boolean culled(Entity entity, Frustum frustum, float partialTicks) {
        if (!on || !RemoteRenderer.config.cullOffscreen || RemoteRenderer.customItemShader() != null || !(entity instanceof Display.ItemDisplay display)
            || !(entity instanceof RrEntity holder)) {
            return false;
        }
        EntityCache cache = holder.remoterenderer$cache();
        if (cache.cullReach < 0.0F || display.renderState() != cache.cullRenderState) {
            return false;
        }
        Display.ItemDisplay.ItemRenderState item = display.itemRenderState();
        if (item == null || item.itemStack() != cache.stack) {
            return false;
        }
        // around the entity: the bone's sphere wherever a turn of the display may have carried it
        float x = (float) (Mth.lerp(partialTicks, entity.xOld, entity.getX()) - frustum.getCamX());
        float y = (float) (Mth.lerp(partialTicks, entity.yOld, entity.getY()) - frustum.getCamY());
        float z = (float) (Mth.lerp(partialTicks, entity.zOld, entity.getZ()) - frustum.getCamZ());
        if (((FrustumAccessor) frustum).remoterenderer$intersection().testSphere(x, y, z, cache.cullReach * 1.1F + 0.5F)) {
            return false;
        }
        RemoteRenderer.STATS.frameMeshCulled++;
        RemoteRenderer.STATS.frameEarlyCulled++;
        return true;
    }

    // ---------------------------------------------------------------- extract

    /** The render state of a plain item display, or null for the vanilla path. */
    public static @Nullable EntityRenderState extract(EntityRenderDispatcher dispatcher, Entity entity, float partialTicks) {
        if (!on || !(entity instanceof Display.ItemDisplay display) || dispatcher.camera == null) {
            return null;
        }
        if (belowName || display.isCustomNameVisible() || display.hasCustomName() || display.isOnFire() || display.isPassenger()
            && display.getVehicle() instanceof AbstractMinecart) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.shouldEntityAppearGlowing(display)) {
            return null;
        }
        Display.RenderState renderState = display.renderState();
        float progress = display.calculateInterpolationProgress(partialTicks);
        if (renderState != null && renderState.shadowRadius().get(progress) > 0.0F) {
            return null;
        }
        DisplayRenderer.ItemDisplayRenderer r = renderer(dispatcher, display);
        if (r == null) {
            return null;
        }
        ItemDisplayEntityRenderState s = r.createRenderState();
        // EntityRenderer.extractRenderState
        s.entityType = display.getType();
        s.x = Mth.lerp(partialTicks, display.xOld, display.getX());
        s.y = Mth.lerp(partialTicks, display.yOld, display.getY());
        s.z = Mth.lerp(partialTicks, display.zOld, display.getZ());
        s.isInvisible = display.isInvisible();
        s.ageInTicks = display.tickCount + partialTicks;
        s.boundingBoxWidth = display.getBbWidth();
        s.boundingBoxHeight = display.getBbHeight();
        s.eyeHeight = display.getEyeHeight();
        s.passengerOffset = null;
        s.distanceToCameraSq = dispatcher.distanceToSqr(display);
        s.nameTag = null;
        s.scoreText = null;
        // only name tags read it, and there are none here
        s.isDiscrete = false;
        s.leashStates = null;
        s.displayFireAnimation = false;
        s.outlineColor = 0;
        s.lightCoords = light(display, renderState, partialTicks);
        // DisplayRenderer.extractRenderState
        s.renderState = renderState;
        s.interpolationProgress = progress;
        s.entityYRot = display.getYRot(partialTicks);
        s.entityXRot = display.getXRot(partialTicks);
        net.minecraft.client.Camera camera = dispatcher.camera;
        s.cameraXRot = camera.xRot();
        s.cameraYRot = camera.yRot();
        // ItemDisplayRenderer.extractRenderState, through RemoteRenderer's item hook
        Display.ItemDisplay.ItemRenderState item = display.itemRenderState();
        if (item != null) {
            ItemModelResolver resolver = ((RrItemDisplayRenderer) r).remoterenderer$resolver();
            // as a WrapOperation's original: the receiver first, then the call's arguments
            Operation<Void> vanilla = args -> {
                ((ItemModelResolver) args[0]).updateForNonLiving((ItemStackRenderState) args[1], (ItemStack) args[2], (ItemDisplayContext) args[3], (Entity) args[4]);
                return null;
            };
            RemoteRenderer.extract(resolver, s.item, item.itemStack(), item.itemTransform(), display, s, vanilla);
        } else {
            s.item.clear();
        }
        // finalizeRenderState: no shadow (checked above)
        s.shadowPieces.clear();
        s.shadowRadius = 0.0F;
        ((RrRenderState) s).remoterenderer$setFast(true);
        RemoteRenderer.STATS.frameFastEntities++;
        if (Stats.TRACE_ROTATION && ((RrRenderState) s).remoterenderer$cache() == null && display instanceof kr.gcb.remoterenderer.duck.RrEntity holder) {
            // test aid: displays RemoteRenderer does not draw get their per-entity record too, for the rotation trace
            ((RrRenderState) s).remoterenderer$set(((RrRenderState) s).remoterenderer$entry(), holder.remoterenderer$cache());
        }
        return s;
    }

    /** The item display renderer, when it is vanilla's own class (a mod's subclass may extract more: vanilla path). */
    private static DisplayRenderer.@Nullable ItemDisplayRenderer renderer(EntityRenderDispatcher dispatcher, Display.ItemDisplay display) {
        if (!rendererLooked) {
            rendererLooked = true;
            EntityRenderer<?, ?> r = dispatcher.getRenderer(display);
            renderer = r != null && r.getClass() == DisplayRenderer.ItemDisplayRenderer.class ? (DisplayRenderer.ItemDisplayRenderer) r : null;
        }
        return renderer;
    }

    /** EntityRenderer.getPackedLightCoords for a display that is not on fire: looked up once per block per frame. */
    private static int light(Display.ItemDisplay display, Display.@Nullable RenderState renderState, float partialTicks) {
        int override = renderState != null ? renderState.brightnessOverride() : -1;
        if (override != -1) {
            return LightCoordsUtil.pack(LightCoordsUtil.block(override), LightCoordsUtil.sky(override));
        }
        Vec3 probe = display.getLightProbePosition(partialTicks);
        long key = BlockPos.asLong(Mth.floor(probe.x), Mth.floor(probe.y), Mth.floor(probe.z));
        int light = LIGHT.getOrDefault(key, -1);
        if (light == -1) {
            Level level = display.level();
            BlockPos pos = BlockPos.of(key);
            light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
            LIGHT.put(key, light);
        }
        return light;
    }

    // ---------------------------------------------------------------- submit

    /**
     * EntityRenderDispatcher.submit + DisplayRenderer.submit for a state the fast extract made: true when done (drawn,
     * or nothing to draw), false for the vanilla path.
     */
    public static boolean submit(EntityRenderState state, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector) {
        if (!(state instanceof ItemDisplayEntityRenderState s) || !((RrRenderState) s).remoterenderer$fast()) {
            return false;
        }
        Display.RenderState renderState = s.renderState;
        boolean drawn = renderState != null && s.hasSubState();
        if (!drawn && !(Stats.TRACE_ROTATION && renderState != null)) {
            // vanilla draws nothing for it either (no name, fire, leash or shadow: the fast extract made sure)
            return true;
        }
        PoseStack.Pose pose = STACK.last();
        pose.set(poseStack.last());
        // displays have no render offset
        pose.translate((float) x, (float) y, (float) z);
        if (renderState.billboardConstraints() != Display.BillboardConstraints.FIXED || s.entityYRot != 0.0F || s.entityXRot != 0.0F) {
            pose.rotate(orientation(renderState, s));
        }
        // (an unturned display: the rotation by the identity quaternion changes nothing, exactly)
        pose.mulPose(renderState.transformation().get(s.interpolationProgress));
        if (!drawn) {
            // test aid only (traceRotation): the pose of a display that draws nothing
            if (((RrRenderState) s).remoterenderer$cache() != null) {
                ((RrRenderState) s).remoterenderer$cache().traceRotation(pose.pose());
            }
            return true;
        }
        return RemoteRenderer.submit(s, STACK, collector, s.lightCoords);
    }

    /** DisplayRenderer.calculateOrientation. */
    private static Quaternionf orientation(Display.RenderState renderState, ItemDisplayEntityRenderState s) {
        float deg = (float) (Math.PI / 180.0);
        return switch (renderState.billboardConstraints()) {
            case FIXED -> ORIENTATION.rotationYXZ(-deg * s.entityYRot, deg * s.entityXRot, 0.0F);
            case HORIZONTAL -> ORIENTATION.rotationYXZ(-deg * s.entityYRot, deg * -s.cameraXRot, 0.0F);
            case VERTICAL -> ORIENTATION.rotationYXZ(-deg * (s.cameraYRot - 180.0F), deg * s.entityXRot, 0.0F);
            case CENTER -> ORIENTATION.rotationYXZ(-deg * (s.cameraYRot - 180.0F), deg * -s.cameraXRot, 0.0F);
        };
    }
}
