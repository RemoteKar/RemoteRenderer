package kr.gcb.remoterenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.api.RemoteRendererApi;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.rig.RigDef;
import kr.gcb.remoterenderer.rig.RigGeometry;
import kr.gcb.remoterenderer.rig.RigManager;
import kr.gcb.remoterenderer.rig.RigPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws rigs. Prepare: the bone displays of one model (same vehicle) become one instance; each bone's pose gives
 * its joint matrix (pose_b * rest_b^-1), clips and API hooks move the other joints, and every joint's skin matrix is
 * written to a texel buffer (3 RGBA32F texels per joint). Execute: one instanced draw per (geometry, part).
 */
public final class RigRenderer implements FeatureRenderer<RigSubmit> {
    public static final FeatureRendererType<RigSubmit> TYPE = FeatureRendererType.create("RemoteRenderer rigs");
    /** Preview: every rig that has this clip plays it (/remoterenderer rigs play <clip>, or -Dremoterenderer.rigClip). */
    public static volatile @Nullable String forcedClip = System.getProperty("remoterenderer.rigClip");
    private static final int INSTANCE_STRIDE = 16;
    private static final int JOINT_BYTES = 48;

    private record InstanceKey(Object vehicle, RigManager.Rig rig) {
    }

    private static final class Instance {
        final Object vehicle;
        final RigManager.Rig rig;
        final RigDef def;
        final Matrix4f[] k;
        final Matrix4f[] rel;
        final boolean[] present;
        final boolean[] hasRel;
        final MeshEntry[] entries;
        final Matrix4f[] world;
        final Matrix4f[] local;
        final boolean[] animated;
        final long born = System.nanoTime();
        long lastSeen;
        long stamp = -1;
        int light;
        int tint = -1;
        @Nullable String clipRequest;
        RigDef.@Nullable Clip clip;
        long clipStart;
        int jointBase;
        boolean physicsOff;
        final RigPhysics.State physics;
        final Matrix4f[] palette;

        Instance(Object vehicle, RigManager.Rig rig) {
            this.vehicle = vehicle;
            this.rig = rig;
            this.def = rig.def;
            int nb = this.def.bones.size(), nj = this.def.joints.size();
            this.k = filled(nb);
            this.rel = filled(nb);
            this.present = new boolean[nb];
            this.hasRel = new boolean[nb];
            this.entries = new MeshEntry[nb];
            this.world = filled(nj);
            this.local = filled(nj);
            this.animated = new boolean[nj];
            this.physics = new RigPhysics.State(nj);
            this.palette = filled(nj);
        }

        private static Matrix4f[] filled(int n) {
            Matrix4f[] a = new Matrix4f[n];
            for (int i = 0; i < n; i++) {
                a[i] = new Matrix4f();
            }
            return a;
        }
    }

    private record Draw(RigGeometry geometry, int instanceOffset, int instances) {
    }

    private final Map<InstanceKey, Instance> instances = new HashMap<>();
    private final List<List<Draw>> groups = new ArrayList<>();
    private final Map<RenderType, PreparedRenderType> prepared = new IdentityHashMap<>();
    private final Map<RigGeometry, List<Instance>> byGeometry = new LinkedHashMap<>();
    private final List<Instance> seen = new ArrayList<>();
    private ByteBuffer instanceData = MemoryUtil.memAlloc(1 << 14).order(ByteOrder.nativeOrder());
    private ByteBuffer jointData = MemoryUtil.memAlloc(1 << 16).order(ByteOrder.nativeOrder());
    private @Nullable MappableRingBuffer jointRing;
    private @Nullable GpuBuffer jointBuffer;
    private @Nullable GpuBufferSlice uploaded;
    private @Nullable PreparedRenderType sheet;
    private @Nullable CompiledRenderPipeline cull;
    private @Nullable CompiledRenderPipeline doubleSided;
    private boolean pack;
    private @Nullable GpuBuffer quadIndices;
    private int maxQuads;
    private long stamp;
    private boolean any;
    private final Matrix4f tmp = new Matrix4f();
    private final org.joml.Vector3f center = new org.joml.Vector3f();
    private final RigPhysics physics = new RigPhysics();
    private final Quaternionf q = new Quaternionf(), q2 = new Quaternionf();

    @Override
    public void beginPrepare(FeatureFrameContext context) {
        this.groups.clear();
        this.prepared.clear();
        this.instanceData.clear();
        this.jointData.clear();
        this.uploaded = null;
        this.jointBuffer = null;
        this.maxQuads = 0;
        this.any = false;
    }

    @Override
    public void prepareGroup(FeatureFrameContext context, List<RigSubmit> submits, boolean strictlyOrdered) {
        List<Draw> draws = new ArrayList<>();
        this.groups.add(draws);
        long now = System.nanoTime();
        long stamp = ++this.stamp;
        this.seen.clear();
        for (RigSubmit s : submits) {
            InstanceKey key = new InstanceKey(s.instance, s.rig);
            Instance inst = this.instances.get(key);
            if (inst == null) {
                inst = new Instance(s.instance, s.rig);
                this.instances.put(key, inst);
            }
            if (inst.stamp != stamp) {
                inst.stamp = stamp;
                Arrays.fill(inst.present, false);
                inst.clipRequest = null;
                this.seen.add(inst);
            }
            int b = s.bone;
            inst.k[b].set(s.pose).mul(inst.def.bones.get(b).restInverse());
            inst.present[b] = true;
            if (s.entry != null) {
                inst.entries[b] = s.entry;
            }
            if (b == inst.def.anchor || !inst.present[inst.def.anchor]) {
                inst.light = s.light;
                inst.tint = s.tint;
            }
            if (s.clip != null) {
                inst.clipRequest = s.clip;
            }
            inst.physicsOff = s.physicsOff;
            inst.lastSeen = now;
        }
        if (this.seen.isEmpty()) {
            return;
        }
        this.byGeometry.clear();
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Vec3 cam = camera.position();
        Frustum frustum = RemoteRenderer.config.cullOffscreen ? camera.getCullFrustum() : null;
        for (Instance inst : this.seen) {
            RigGeometry g = inst.def.boneGeometry() ? inst.rig.boneGeometry(inst.entries, now) : inst.rig.meshGeometry();
            if (g == null || g.isClosed()) {
                continue;
            }
            this.pose(inst, now, g, cam);
            // off screen: nothing drawn, no texture loaded for it (vanilla never culls display entities)
            if (frustum != null && !this.visible(inst, g, frustum, cam)) {
                RemoteRenderer.STATS.frameRigCulled++;
                continue;
            }
            if (!texturesReady(g, now)) {
                continue;
            }
            this.writeJoints(inst);
            this.byGeometry.computeIfAbsent(g, x -> new ArrayList<>()).add(inst);
            inst.rig.instances++;
        }
        for (Map.Entry<RigGeometry, List<Instance>> e : this.byGeometry.entrySet()) {
            RigGeometry g = e.getKey();
            List<Instance> list = e.getValue();
            int offset = this.instanceData.position();
            this.ensureInstanceRoom(list.size() * INSTANCE_STRIDE);
            for (Instance inst : list) {
                this.instanceData.putInt(inst.jointBase).putInt(inst.light).putInt(inst.tint).putInt(0);
            }
            draws.add(new Draw(g, offset, list.size()));
            this.maxQuads = Math.max(this.maxQuads, g.maxQuads);
            for (RigGeometry.Part p : g.parts) {
                if (p.renderType() != null) {
                    this.prepared.computeIfAbsent(p.renderType(), RenderType::prepare);
                }
            }
            RemoteRenderer.STATS.frameRigInstances += list.size();
            RemoteRenderer.STATS.frameRigVertices += (long) g.vertexCount * list.size();
        }
        if (!draws.isEmpty()) {
            this.any = true;
        }
    }

    /** Uploads finished texture decodes, starts missing ones; false while one is still loading (the model waits). */
    private static boolean texturesReady(RigGeometry g, long now) {
        boolean ready = true;
        for (RigGeometry.Part p : g.parts) {
            if (p.texture() != null && p.texture().acquire(now) == null && !p.texture().failed()) {
                ready = false;
            }
        }
        return ready;
    }

    /** Is any joint's bounding sphere (moved by its skin matrix) in the view frustum? */
    private boolean visible(Instance inst, RigGeometry g, Frustum frustum, Vec3 cam) {
        float[] b = g.jointBounds;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        org.joml.Vector3f c = this.center;
        for (int j = 0; j < inst.palette.length; j++) {
            float r = b[j * 4 + 3];
            if (r < 0.0F) {
                continue;
            }
            Matrix4f m = inst.palette[j];
            m.transformPosition(b[j * 4], b[j * 4 + 1], b[j * 4 + 2], c);
            float s = r * (float) Math.sqrt(Math.max(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02(),
                Math.max(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12(), m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22())));
            minX = Math.min(minX, c.x - s);
            minY = Math.min(minY, c.y - s);
            minZ = Math.min(minZ, c.z - s);
            maxX = Math.max(maxX, c.x + s);
            maxY = Math.max(maxY, c.y + s);
            maxZ = Math.max(maxZ, c.z + s);
        }
        if (minX > maxX) {
            return true;
        }
        return frustum.isVisible(new AABB(minX + cam.x, minY + cam.y, minZ + cam.z, maxX + cam.x, maxY + cam.y, maxZ + cam.z));
    }

    /** The skin matrices (palette) into jointData. */
    private void writeJoints(Instance inst) {
        int nj = inst.palette.length;
        this.ensureJointRoom(nj * JOINT_BYTES);
        inst.jointBase = this.jointData.position() / JOINT_BYTES;
        ByteBuffer out = this.jointData;
        for (int j = 0; j < nj; j++) {
            Matrix4f m = inst.palette[j];
            out.putFloat(m.m00()).putFloat(m.m10()).putFloat(m.m20()).putFloat(m.m30());
            out.putFloat(m.m01()).putFloat(m.m11()).putFloat(m.m21()).putFloat(m.m31());
            out.putFloat(m.m02()).putFloat(m.m12()).putFloat(m.m22()).putFloat(m.m32());
        }
        RemoteRenderer.STATS.frameRigJoints += nj;
    }

    /** Joint and skin matrices of one instance (inst.world, inst.palette). */
    private void pose(Instance inst, long now, RigGeometry g, Vec3 cam) {
        RigDef d = inst.def;
        int nb = d.bones.size();
        int anchor = d.anchor;
        boolean anchorHere = inst.present[anchor];
        int ref = anchor;
        if (!anchorHere) {
            for (int b = 0; b < nb; b++) {
                if (inst.present[b]) {
                    ref = b;
                    break;
                }
            }
        }
        // bones not drawn this frame (culled, not sent yet): keep where they were relative to the anchor
        if (anchorHere) {
            Matrix4f inv = this.tmp.set(inst.k[anchor]).invert();
            for (int b = 0; b < nb; b++) {
                if (inst.present[b] && b != anchor) {
                    inv.mul(inst.k[b], inst.rel[b]);
                    inst.hasRel[b] = true;
                }
            }
        }
        for (int b = 0; b < nb; b++) {
            if (!inst.present[b]) {
                if (anchorHere && inst.hasRel[b]) {
                    inst.k[anchor].mul(inst.rel[b], inst.k[b]);
                } else {
                    inst.k[b].set(inst.k[ref]);
                }
            }
        }

        // clip
        String want = forcedClip != null && d.clips.containsKey(forcedClip) ? forcedClip : inst.clipRequest != null ? inst.clipRequest : d.autoplay;
        RigDef.Clip clip = want == null ? null : d.clips.get(want);
        if (clip != null && clip.data == null) {
            clip = null;
        }
        if (clip != inst.clip) {
            inst.clip = clip;
            inst.clipStart = now;
        }
        Arrays.fill(inst.animated, false);
        if (clip != null) {
            this.sample(inst, clip, (now - inst.clipStart) / 1.0e9F);
        }

        // joints, parents first
        List<RigDef.Joint> joints = d.joints;
        for (int j = 0; j < joints.size(); j++) {
            RigDef.Joint jt = joints.get(j);
            Matrix4f w = inst.world[j];
            Matrix4f loc = inst.animated[j] ? inst.local[j] : jt.local;
            if (jt.bone >= 0) {
                inst.k[jt.bone].mul(jt.restWorld, w);
            } else if (jt.parent >= 0) {
                inst.world[jt.parent].mul(loc, w);
            } else {
                inst.k[ref].mul(d.skeletonRoot, w).mul(loc);
            }
        }
        if (d.hasSprings && d.physics && RemoteRenderer.config.rigPhysics && !inst.physicsOff) {
            if (g.springTails == null) {
                g.springTails = RigPhysics.tails(d, g.jointFar);
            }
            this.physics.apply(d, g.springTails, inst.world, inst.physics, cam.x, cam.y, cam.z, now);
        }
        List<RemoteRendererApi.JointHook> hooks = RemoteRendererApi.hooks();
        if (!hooks.isEmpty()) {
            float seconds = (now - inst.born) / 1.0e9F;
            Object vehicle = inst.vehicle;
            for (RemoteRendererApi.JointHook h : hooks) {
                try {
                    h.adjust(d, vehicle, inst.world, seconds);
                } catch (RuntimeException ex) {
                    RemoteRenderer.LOG.warn("Rig joint hook failed", ex);
                    RemoteRendererApi.removeJointHook(h);
                }
            }
        }
        for (int j = 0; j < joints.size(); j++) {
            inst.world[j].mul(joints.get(j).inverseBind, inst.palette[j]);
        }
    }

    /** Local transforms of the clip's joints at time t (linear / nlerp between the sampled frames). */
    private void sample(Instance inst, RigDef.Clip clip, float t) {
        float[] data = clip.data;
        int frames = clip.frames;
        float dur = clip.duration();
        if (dur > 0.0F) {
            t = clip.loop ? t % dur : Math.min(t, dur);
        } else {
            t = 0.0F;
        }
        float f = t * clip.fps;
        int i0 = Math.min((int) f, frames - 1);
        int i1 = Math.min(i0 + 1, frames - 1);
        float a = f - i0;
        int stride = clip.joints.length * 10;
        for (int k = 0; k < clip.joints.length; k++) {
            int o0 = i0 * stride + k * 10, o1 = i1 * stride + k * 10;
            float tx = lerp(data[o0], data[o1], a), ty = lerp(data[o0 + 1], data[o1 + 1], a), tz = lerp(data[o0 + 2], data[o1 + 2], a);
            this.q.set(data[o0 + 3], data[o0 + 4], data[o0 + 5], data[o0 + 6]);
            this.q2.set(data[o1 + 3], data[o1 + 4], data[o1 + 5], data[o1 + 6]);
            this.q.nlerp(this.q2, a);
            float sx = lerp(data[o0 + 7], data[o1 + 7], a), sy = lerp(data[o0 + 8], data[o1 + 8], a), sz = lerp(data[o0 + 9], data[o1 + 9], a);
            int j = clip.joints[k];
            inst.local[j].translationRotateScale(tx, ty, tz, this.q.x, this.q.y, this.q.z, this.q.w, sx, sy, sz);
            inst.animated[j] = true;
        }
    }

    private static float lerp(float x, float y, float a) {
        return x + (y - x) * a;
    }

    private void ensureInstanceRoom(int bytes) {
        if (this.instanceData.remaining() < bytes) {
            int size = Math.max(this.instanceData.position() + bytes, this.instanceData.capacity() * 2);
            this.instanceData = MemoryUtil.memRealloc(this.instanceData, size).order(ByteOrder.nativeOrder());
        }
    }

    private void ensureJointRoom(int bytes) {
        if (this.jointData.remaining() < bytes) {
            int size = Math.max(this.jointData.position() + bytes, this.jointData.capacity() * 2);
            this.jointData = MemoryUtil.memRealloc(this.jointData, size).order(ByteOrder.nativeOrder());
        }
    }

    @Override
    public void finishPrepare(FeatureFrameContext context) {
        if (!this.any) {
            return;
        }
        this.pack = PackShaders.active();
        this.cull = this.pack ? PackShaders.rig(false) : MeshPipelines.get(MeshPipelines.RIG);
        this.doubleSided = this.pack ? PackShaders.rig(true) : MeshPipelines.get(MeshPipelines.RIG_DOUBLE);
        var device = RenderSystem.getDevice();
        if (this.instanceData.position() > 0) {
            this.instanceData.flip();
            this.uploaded = device.createCommandEncoder().transientMemory().uploadGpu(this.instanceData, 16L, GpuBuffer.USAGE_VERTEX);
        }
        int jointBytes = this.jointData.position();
        if (jointBytes > 0) {
            if (this.jointRing == null || this.jointRing.size() < jointBytes) {
                if (this.jointRing != null) {
                    this.jointRing.close();
                }
                int size = Math.max(jointBytes * 2, 1 << 16);
                this.jointRing = new MappableRingBuffer(() -> "RemoteRenderer rig joints", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, size);
            }
            this.jointRing.rotate();
            GpuBuffer buffer = this.jointRing.currentBuffer();
            try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
                this.jointData.flip();
                view.data().put(this.jointData);
            }
            this.jointBuffer = buffer;
        }
        for (List<Draw> draws : this.groups) {
            for (Draw d : draws) {
                d.geometry().ensureUploaded();
            }
        }
        this.quadIndices = this.maxQuads > 0 ? QuadIndices.ensure(this.maxQuads) : null;
        this.sheet = Sheets.cutoutItemSheet().prepare();
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage stage, RenderPass renderPass, int groupIndex, List<RigSubmit> submits,
                             boolean strictlyOrdered) {
        GpuBufferSlice instances = this.uploaded;
        GpuBuffer joints = this.jointBuffer;
        if (stage != null || instances == null || joints == null || groupIndex >= this.groups.size()) {
            return;
        }
        CompiledRenderPipeline current = null;
        GpuSampler linear = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, true);
        GpuSampler nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, true);
        for (Draw d : this.groups.get(groupIndex)) {
            RigGeometry g = d.geometry();
            GpuBuffer vertices = g.vertexBuffer();
            if (vertices == null || g.isClosed()) {
                continue;
            }
            for (RigGeometry.Part p : g.parts) {
                CompiledRenderPipeline pipeline = p.doubleSided() ? this.doubleSided : this.cull;
                PreparedRenderType prt = p.renderType() != null ? this.prepared.get(p.renderType()) : this.sheet;
                if (pipeline == null || prt == null) {
                    continue;
                }
                GpuTextureView own = null;
                if (p.texture() != null) {
                    own = p.texture().view();
                    if (own == null) {
                        continue;
                    }
                }
                if (p.quads() && this.quadIndices == null) {
                    continue;
                }
                if (pipeline != current) {
                    renderPass.setPipeline(pipeline);
                    current = pipeline;
                }
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", prt.dynamicTransforms());
                for (PreparedRenderType.Texture t : prt.textures()) {
                    // the pack's item shader reads the overlay texture (Sampler1) too; ours does not declare it
                    if (!this.pack && t.name().equals("Sampler1") || own != null && t.name().equals("Sampler0")) {
                        continue;
                    }
                    renderPass.setUniform(t.name(), t.textureView(), t.sampler());
                }
                if (own != null) {
                    renderPass.setUniform("Sampler0", own, p.linear() ? linear : nearest);
                }
                renderPass.setUniform("RigJoints", joints);
                renderPass.setVertexBuffer(1, instances.slice(d.instanceOffset(), (long) d.instances() * INSTANCE_STRIDE));
                if (p.quads()) {
                    renderPass.setVertexBuffer(0, vertices.slice((long) p.first() * 4 * RigGeometry.VERTEX_SIZE, (long) p.count() * 4 * RigGeometry.VERTEX_SIZE));
                    renderPass.setIndexBuffer(this.quadIndices, IndexType.INT);
                    renderPass.drawIndexed(p.count() * 6, d.instances(), 0, 0, 0);
                } else {
                    GpuBuffer indices = g.indexBuffer();
                    if (indices == null) {
                        continue;
                    }
                    renderPass.setVertexBuffer(0, vertices.slice(0L, vertices.size()));
                    renderPass.setIndexBuffer(indices, IndexType.INT);
                    renderPass.drawIndexed(p.count(), d.instances(), p.first(), 0, 0);
                }
                RemoteRenderer.STATS.frameDraws++;
            }
        }
    }

    @Override
    public void finishExecute(FeatureFrameContext context) {
        RigSubmit.releaseAll();
        this.groups.clear();
        this.prepared.clear();
        this.uploaded = null;
        if (this.any) {
            for (RigManager.Rig r : RigManager.rigs()) {
                r.lastInstances = r.instances;
                r.instances = 0;
            }
        }
        this.any = false;
        long now = System.nanoTime();
        for (Iterator<Instance> it = this.instances.values().iterator(); it.hasNext(); ) {
            if (now - it.next().lastSeen > 5_000_000_000L) {
                it.remove();
            }
        }
        RigManager.endFrame(now);
    }

    @Override
    public void close() {
        MemoryUtil.memFree(this.instanceData);
        MemoryUtil.memFree(this.jointData);
        if (this.jointRing != null) {
            this.jointRing.close();
        }
    }
}
