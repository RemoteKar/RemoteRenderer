package kr.gcb.remoterenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.mesh.MeshCache;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.objmc.ObjmcColor;
import kr.gcb.remoterenderer.objmc.ObjmcModel;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws MeshSubmits: in the prepare phase all instances of a group are written into one per-frame buffer
 * (grouped by baked model), in the execute phase every (model, part) is one instanced draw call.
 */
public final class MeshFeatureRenderer implements FeatureRenderer<MeshSubmit> {
    public static final FeatureRendererType<MeshSubmit> TYPE = FeatureRendererType.create("RemoteRenderer");
    static final int MESH_STRIDE = 108;
    static final int OBJMC_STRIDE = 108;
    private static final int MESH_VERTEX_SIZE = 32;
    private static final int OBJMC_VERTEX_SIZE = 4;

    private record Draw(boolean objmc, MeshEntry entry, RenderType renderType, int firstVertex, int quadCount, int byteOffset, int instances,
                        @Nullable ObjmcModel model) {
    }

    private final List<List<Draw>> groups = new ArrayList<>();
    private final Map<RenderType, PreparedRenderType> prepared = new IdentityHashMap<>();
    private final Map<ObjmcModel, GpuTextureView> objmcViews = new IdentityHashMap<>();
    private ByteBuffer scratch = MemoryUtil.memAlloc(1 << 20).order(ByteOrder.nativeOrder());
    private @Nullable GpuBufferSlice uploaded;
    private @Nullable GpuBuffer indexBuffer;
    private @Nullable CompiledRenderPipeline meshPipeline;
    private @Nullable CompiledRenderPipeline objmcPipeline;
    private int maxQuads;
    private boolean any;

    private final ObjmcColor color = new ObjmcColor();
    private final Vector3f[] corners = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    private final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
    private final Matrix3f linear = new Matrix3f();
    private final Matrix4f placement = new Matrix4f();
    private final Matrix4f pose = new Matrix4f();
    private final Map<MeshEntry, ArrayList<MeshSubmit>> byEntry = new LinkedHashMap<>();
    private final java.util.ArrayDeque<ArrayList<MeshSubmit>> listPool = new java.util.ArrayDeque<>();

    @Override
    public void beginPrepare(FeatureFrameContext context) {
        this.groups.clear();
        this.prepared.clear();
        this.objmcViews.clear();
        this.scratch.clear();
        this.uploaded = null;
        this.maxQuads = 0;
        this.any = false;
    }

    @Override
    public void prepareGroup(FeatureFrameContext context, List<MeshSubmit> submits, boolean strictlyOrdered) {
        List<Draw> draws = new ArrayList<>();
        this.groups.add(draws);
        // group by baked model; the map and its lists are reused from frame to frame
        Map<MeshEntry, ArrayList<MeshSubmit>> byEntry = this.byEntry;
        for (MeshSubmit s : submits) {
            if (!s.entry.isRetired()) {
                ArrayList<MeshSubmit> list = byEntry.get(s.entry);
                if (list == null) {
                    list = this.listPool.isEmpty() ? new ArrayList<>() : this.listPool.pop();
                    byEntry.put(s.entry, list);
                }
                list.add(s);
            }
        }
        long now = System.nanoTime();
        for (Map.Entry<MeshEntry, ArrayList<MeshSubmit>> group : byEntry.entrySet()) {
            MeshEntry entry = group.getKey();
            List<MeshSubmit> list = group.getValue();
            long u0 = System.nanoTime();
            entry.ensureUploaded();
            RemoteRenderer.STATS.frameUploadNanos += System.nanoTime() - u0;
            entry.lastUsedNanos = now;
            if (!entry.parts.isEmpty() && entry.vertexBuffer() != null) {
                int offset = this.scratch.position();
                for (MeshSubmit s : list) {
                    this.writeMeshInstance(s);
                }
                for (MeshEntry.Part part : entry.parts) {
                    draws.add(new Draw(false, entry, part.renderType(), part.firstVertex(), part.quadCount(), offset, list.size(), null));
                    this.maxQuads = Math.max(this.maxQuads, part.quadCount());
                    this.prepared.computeIfAbsent(part.renderType(), RenderType::prepare);
                }
                RemoteRenderer.STATS.frameInstances += list.size();
                RemoteRenderer.STATS.frameQuads += (long) entry.solidQuads * list.size();
            }
            for (int k = 0; k < entry.objmcParts.size(); k++) {
                MeshEntry.ObjmcPart part = entry.objmcParts.get(k);
                ObjmcModel model = part.model();
                if (!model.visibleInWorld || entry.objmcIdBuffer() == null) {
                    continue;
                }
                long u1 = System.nanoTime();
                GpuTextureView view = this.objmcViews.computeIfAbsent(model, m -> m.ensureUploaded());
                RemoteRenderer.STATS.frameUploadNanos += System.nanoTime() - u1;
                if (view == null) {
                    continue;
                }
                int offset = this.scratch.position();
                for (MeshSubmit s : list) {
                    this.writeObjmcInstance(s, part, k < s.objmcTints.length ? s.objmcTints[k] : -1);
                }
                draws.add(new Draw(true, entry, part.renderType(), part.firstVertex(), part.quadCount(), offset, list.size(), model));
                this.maxQuads = Math.max(this.maxQuads, part.quadCount());
                this.prepared.computeIfAbsent(part.renderType(), RenderType::prepare);
                RemoteRenderer.STATS.frameInstances += list.size();
                RemoteRenderer.STATS.frameQuads += (long) part.quadCount() * list.size();
            }
        }
        for (ArrayList<MeshSubmit> list : byEntry.values()) {
            list.clear();
            this.listPool.push(list);
        }
        byEntry.clear();
        if (!draws.isEmpty()) {
            this.any = true;
        }
    }

    private void ensureRoom(int bytes) {
        if (this.scratch.remaining() < bytes) {
            int need = this.scratch.position() + bytes;
            int size = Math.max(need, this.scratch.capacity() * 2);
            this.scratch = MemoryUtil.memRealloc(this.scratch, size).order(ByteOrder.nativeOrder());
        }
    }

    private void writeMeshInstance(MeshSubmit s) {
        this.ensureRoom(MESH_STRIDE);
        ByteBuffer buf = this.scratch;
        long at = MemoryUtil.memAddress(buf);
        float[] m = s.matrix;
        for (int i = 0; i < 25; i++) {
            MemoryUtil.memPutFloat(at + i * 4L, m[i]);
        }
        // light and overlay as two shorts each (u, v): with native little-endian order that is the int itself
        MemoryUtil.memPutInt(at + 100, s.light);
        MemoryUtil.memPutInt(at + 104, s.overlay);
        buf.position(buf.position() + MESH_STRIDE);
    }

    private void writeObjmcInstance(MeshSubmit s, MeshEntry.ObjmcPart part, int tint) {
        ObjmcModel model = part.model();
        ObjmcColor col = this.color.decode(model, tint);
        Matrix4f pose = this.pose.set(s.matrix);
        for (int i = 0; i < 4; i++) {
            pose.transformPosition(part.corners()[i], this.corners[i]);
        }
        if (model.autoRotate) {
            // "normal estimated rotation calculation from The Der Discohund": the dummy quad's own edges
            float size = this.corners[0].distance(this.corners[1]);
            this.corners[0].sub(this.corners[3], this.a).normalize();
            this.corners[0].sub(this.corners[1], this.b).normalize();
            this.a.cross(this.b, this.c);
            this.linear.set(this.a, this.b, this.c).scale(size);
        } else {
            col.rotation(this.linear);
        }
        this.linear.scale(col.scale);
        this.placement.set(this.linear).setTranslation(this.corners[2]);

        this.ensureRoom(OBJMC_STRIDE);
        ByteBuffer buf = this.scratch;
        int pos = buf.position();
        this.placement.get(pos, buf);
        buf.position(pos + 64);
        buf.putFloat(col.timeColor).putFloat(col.autoplay ? 1.0F : 0.0F).putFloat(model.duration).putFloat(model.frames);
        buf.putInt(model.vertexCount).putInt(model.easing).putInt(model.noShadow ? 1 : 0).putInt(0);
        buf.put(unorm(col.overlayR)).put(unorm(col.overlayG)).put(unorm(col.overlayB)).put((byte) 255);
        int light = s.light;
        buf.putShort((short) (light & 0xFFFF)).putShort((short) (light >>> 16 & 0xFFFF)).putShort((short) 0).putShort((short) 0);
    }

    private static byte unorm(float f) {
        return (byte) Math.round(Math.clamp(f, 0.0F, 1.0F) * 255.0F);
    }

    @Override
    public void finishPrepare(FeatureFrameContext context) {
        if (!this.any) {
            return;
        }
        // a pack's own item shader, instanced, when one is loaded (PackShaders); else ours
        this.meshPipeline = PackShaders.active() ? PackShaders.mesh() : MeshPipelines.get(MeshPipelines.MESH);
        this.objmcPipeline = MeshPipelines.get(MeshPipelines.OBJMC);
        if (this.meshPipeline == null) {
            RemoteRenderer.disable("the mesh pipeline did not compile (see the log above)");
        }
        if (this.scratch.position() > 0) {
            this.scratch.flip();
            this.uploaded = RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadGpu(this.scratch, 16L, GpuBuffer.USAGE_VERTEX);
        }
        this.indexBuffer = QuadIndices.ensure(this.maxQuads);
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage stage, RenderPass renderPass, int groupIndex, List<MeshSubmit> submits,
                             boolean strictlyOrdered) {
        GpuBufferSlice instances = this.uploaded;
        GpuBuffer indices = this.indexBuffer;
        if (stage != null || instances == null || indices == null || groupIndex >= this.groups.size()) {
            return;
        }
        CompiledRenderPipeline current = null;
        for (Draw d : this.groups.get(groupIndex)) {
            CompiledRenderPipeline pipeline = d.objmc ? this.objmcPipeline : this.meshPipeline;
            PreparedRenderType prt = this.prepared.get(d.renderType);
            GpuBuffer vertices = d.objmc ? d.entry.objmcIdBuffer() : d.entry.vertexBuffer();
            if (pipeline == null || prt == null || vertices == null || d.entry.isRetired()) {
                continue;
            }
            if (pipeline != current) {
                renderPass.setPipeline(pipeline);
                current = pipeline;
            }
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", prt.dynamicTransforms());
            for (PreparedRenderType.Texture t : prt.textures()) {
                if (d.objmc && t.name().equals("Sampler1")) {
                    continue;
                }
                renderPass.setUniform(t.name(), t.textureView(), t.sampler());
            }
            if (d.objmc) {
                GpuTextureView view = this.objmcViews.get(d.model);
                if (view == null) {
                    continue;
                }
                renderPass.setUniform("ObjmcData", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            int vertexSize = d.objmc ? OBJMC_VERTEX_SIZE : MESH_VERTEX_SIZE;
            int stride = d.objmc ? OBJMC_STRIDE : MESH_STRIDE;
            renderPass.setVertexBuffer(0, vertices.slice((long) d.firstVertex * vertexSize, (long) d.quadCount * 4 * vertexSize));
            // a sub-slice of the slice: transient buffers refuse buffer().slice()
            renderPass.setVertexBuffer(1, instances.slice(d.byteOffset, (long) d.instances * stride));
            renderPass.setIndexBuffer(indices, IndexType.INT);
            renderPass.drawIndexed(d.quadCount * 6, d.instances, 0, 0, 0);
            RemoteRenderer.STATS.frameDraws++;
        }
    }

    @Override
    public void finishExecute(FeatureFrameContext context) {
        MeshSubmit.releaseAll();
        this.groups.clear();
        this.prepared.clear();
        this.objmcViews.clear();
        this.uploaded = null;
        this.any = false;
        MeshCache.endFrame(System.nanoTime(), RemoteRenderer.config.evictSeconds);
    }

    @Override
    public void close() {
        MemoryUtil.memFree(this.scratch);
    }
}
