package kr.gcb.remoterenderer.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.util.List;
import kr.gcb.remoterenderer.objmc.ObjmcModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * One distinct item look, baked: the solid quads of all its layers in item space (the space the vanilla
 * renderer is in when it calls ItemStackRenderState.submit), grouped by render type, in one static vertex
 * buffer; objmc parts with their vertex ids; and the translucent layers, which stay with the vanilla renderer
 * so that they keep its sorting / order independent transparency.
 */
public final class MeshEntry {
    private static int nextId;

    public final int id = nextId++;
    public final ItemDisplayContext context;
    public final List<Part> parts;
    public final List<ObjmcPart> objmcParts;
    public final List<TranslucentLayer> translucent;
    public final int solidQuads;
    public final int vertexBytes;

    private @Nullable ByteBuffer vertexData;
    /** kept after the upload when the model is a rig bone (its vertices go into the rig geometry) */
    private @Nullable ByteBuffer retained;
    public volatile boolean retainCpu;
    private @Nullable ByteBuffer objmcIdData;
    private @Nullable GpuBuffer vertexBuffer;
    private @Nullable GpuBuffer objmcIdBuffer;
    public volatile long lastUsedNanos = System.nanoTime();
    public long bakeNanos;
    /** min xyz, max xyz of the baked solid vertices (model space of the item) */
    public final float[] bounds = new float[6];
    private boolean retired;

    MeshEntry(ItemDisplayContext context, List<Part> parts, List<ObjmcPart> objmcParts, List<TranslucentLayer> translucent, int solidQuads,
              @Nullable ByteBuffer vertexData, @Nullable ByteBuffer objmcIdData) {
        this.context = context;
        this.parts = parts;
        this.objmcParts = objmcParts;
        this.translucent = translucent;
        this.solidQuads = solidQuads;
        this.vertexData = vertexData;
        this.objmcIdData = objmcIdData;
        this.vertexBytes = (vertexData == null ? 0 : vertexData.remaining()) + (objmcIdData == null ? 0 : objmcIdData.remaining());
        if (vertexData != null && vertexData.remaining() >= 32) {
            java.util.Arrays.fill(this.bounds, 0, 3, Float.POSITIVE_INFINITY);
            java.util.Arrays.fill(this.bounds, 3, 6, Float.NEGATIVE_INFINITY);
            ByteBuffer d = vertexData.duplicate().order(java.nio.ByteOrder.nativeOrder());
            for (int at = d.position(); at + 32 <= d.limit(); at += 32) {
                for (int k = 0; k < 3; k++) {
                    float v = d.getFloat(at + k * 4);
                    this.bounds[k] = Math.min(this.bounds[k], v);
                    this.bounds[k + 3] = Math.max(this.bounds[k + 3], v);
                }
            }
        }
    }

    public boolean hasGpuWork() {
        return !this.parts.isEmpty() || !this.objmcParts.isEmpty();
    }

    /** Creates the GPU buffers; prepare phase only (never inside a render pass). */
    public void ensureUploaded() {
        if (this.retired) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (this.vertexData != null) {
            ByteBuffer data = this.vertexData;
            this.vertexData = null;
            this.vertexBuffer = device.createBuffer(() -> "RemoteRenderer model #" + this.id, GpuBuffer.USAGE_VERTEX, data);
            if (this.retainCpu) {
                this.retained = data;
            } else {
                MemoryUtil.memFree(data);
            }
        }
        if (this.objmcIdData != null) {
            ByteBuffer data = this.objmcIdData;
            this.objmcIdData = null;
            this.objmcIdBuffer = device.createBuffer(() -> "RemoteRenderer objmc ids #" + this.id, GpuBuffer.USAGE_VERTEX, data);
            MemoryUtil.memFree(data);
        }
    }

    /** The baked vertices on the CPU (before the upload, or kept for a rig) as a view; null if gone. Render thread. */
    public @Nullable ByteBuffer cpuVertices() {
        ByteBuffer d = this.vertexData != null ? this.vertexData : this.retained;
        return d == null || this.retired ? null : d.duplicate().order(java.nio.ByteOrder.nativeOrder());
    }

    public @Nullable GpuBuffer vertexBuffer() {
        return this.vertexBuffer;
    }

    public @Nullable GpuBuffer objmcIdBuffer() {
        return this.objmcIdBuffer;
    }

    public boolean isRetired() {
        return this.retired;
    }

    /** Frees everything. Render thread, outside a render pass. */
    public void retire() {
        if (this.retired) {
            return;
        }
        this.retired = true;
        if (this.retained != null) {
            MemoryUtil.memFree(this.retained);
            this.retained = null;
        }
        if (this.vertexData != null) {
            MemoryUtil.memFree(this.vertexData);
            this.vertexData = null;
        }
        if (this.objmcIdData != null) {
            MemoryUtil.memFree(this.objmcIdData);
            this.objmcIdData = null;
        }
        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
        if (this.objmcIdBuffer != null) {
            this.objmcIdBuffer.close();
            this.objmcIdBuffer = null;
        }
    }

    /** Solid quads of one render type: vertices [firstVertex, firstVertex + quadCount * 4) of the vertex buffer. */
    public record Part(RenderType renderType, int firstVertex, int quadCount) {
    }

    /**
     * The dummy faces of an objmc model. Vertex ids [firstVertex, firstVertex + quadCount * 4) of the id buffer.
     * corners: the first dummy quad's corners in item space (all dummy quads of an objmc model are the same quad);
     * objmc anchors the model at corner 2 and "autorotate" reads the orientation from corners 0, 1 and 3.
     */
    public record ObjmcPart(ObjmcModel model, RenderType renderType, int firstVertex, int quadCount, Vector3f[] corners, int tintIndex,
                            int layerIndex) {
    }

    /** A layer's translucent quads, handed to the vanilla renderer with the layer's transform. */
    public record TranslucentLayer(Matrix4f pose, int[] tints, ItemQuads quads) {
    }
}
