package kr.gcb.remoterenderer.rig;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Skinned vertices on the GPU. Vertex (40 bytes): Position RGB32F, Color RGBA8, UV0 RG32F, Normal RGBA8 snorm,
 * Extra RGBA8 (x = light emission), Joints RGBA8 uint, Weights RGBA8 unorm.
 *
 * "bones" geometry is built here from the bones' baked item models (quads, item atlas); a mesh file brings its own
 * triangles and textures.
 */
public final class RigGeometry {
    public static final int VERTEX_SIZE = 40;
    private static final int SRC_SIZE = 32;

    /**
     * One draw. Quads (bones geometry): firstVertex/quadCount with the shared quad index buffer; triangles (mesh file):
     * firstIndex/indexCount in this geometry's own index buffer.
     */
    public record Part(@Nullable RenderType renderType, @Nullable RigTexture texture, boolean quads, int first, int count, boolean doubleSided,
                       boolean linear) {
    }

    public final List<Part> parts;
    public final int vertexCount;
    public final int maxQuads;
    /**
     * Per joint, rig space at rest, from the vertices it moves most: a bounding sphere (cx cy cz r; r < 0 = none), for
     * culling, and the vertex farthest from the joint (x y z; NaN = none), the default tail of a spring.
     */
    public final float[] jointBounds;
    public final float[] jointFar;
    /** spring tails per joint (RigPhysics.tails), made on first use */
    public float @Nullable [] springTails;
    private @Nullable ByteBuffer vertexData;
    private @Nullable ByteBuffer indexData;
    private @Nullable GpuBuffer vertexBuffer;
    private @Nullable GpuBuffer indexBuffer;
    private boolean closed;

    RigGeometry(List<Part> parts, ByteBuffer vertexData, @Nullable ByteBuffer indexData, int vertexCount, int maxQuads, RigDef rig) {
        this.parts = parts;
        this.vertexData = vertexData;
        this.indexData = indexData;
        this.vertexCount = vertexCount;
        this.maxQuads = maxQuads;
        int nj = rig.joints.size();
        this.jointBounds = new float[nj * 4];
        this.jointFar = new float[nj * 3];
        measure(rig, vertexData, vertexCount, this.jointBounds, this.jointFar);
    }

    /** Prepare phase only. */
    public boolean ensureUploaded() {
        if (this.closed) {
            return false;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (this.vertexData != null) {
            ByteBuffer d = this.vertexData;
            this.vertexData = null;
            this.vertexBuffer = device.createBuffer(() -> "RemoteRenderer rig vertices", GpuBuffer.USAGE_VERTEX, d);
            MemoryUtil.memFree(d);
        }
        if (this.indexData != null) {
            ByteBuffer d = this.indexData;
            this.indexData = null;
            this.indexBuffer = device.createBuffer(() -> "RemoteRenderer rig indices", GpuBuffer.USAGE_INDEX, d);
            MemoryUtil.memFree(d);
        }
        return this.vertexBuffer != null;
    }

    public @Nullable GpuBuffer vertexBuffer() {
        return this.vertexBuffer;
    }

    public @Nullable GpuBuffer indexBuffer() {
        return this.indexBuffer;
    }

    public boolean isClosed() {
        return this.closed;
    }

    void close() {
        this.closed = true;
        if (this.vertexData != null) {
            MemoryUtil.memFree(this.vertexData);
            this.vertexData = null;
        }
        if (this.indexData != null) {
            MemoryUtil.memFree(this.indexData);
            this.indexData = null;
        }
        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
        if (this.indexBuffer != null) {
            this.indexBuffer.close();
            this.indexBuffer = null;
        }
    }

    // ---------------------------------------------------------------- "bones" geometry

    private record Src(int bone, MeshEntry entry, ByteBuffer data, MeshEntry.Part part) {
    }

    /**
     * The bones' baked models in rig space (rest_b * the half turn ItemDisplayRenderer adds), weighted by the rig's
     * blends. Null if a bone has no baked model with vertex data yet.
     */
    static @Nullable RigGeometry fromBones(RigDef rig, MeshEntry[] entries) {
        Map<RenderType, List<Src>> byType = new LinkedHashMap<>();
        int quads = 0;
        for (int b = 0; b < entries.length; b++) {
            MeshEntry e = entries[b];
            if (e == null) {
                continue;
            }
            ByteBuffer data = e.cpuVertices();
            if (data == null) {
                return null;
            }
            for (MeshEntry.Part p : e.parts) {
                byType.computeIfAbsent(p.renderType(), k -> new ArrayList<>()).add(new Src(b, e, data, p));
                quads += p.quadCount();
            }
        }
        if (quads == 0) {
            return null;
        }
        int vertices = quads * 4;
        ByteBuffer out = MemoryUtil.memAlloc(vertices * VERTEX_SIZE).order(ByteOrder.nativeOrder());
        List<Part> parts = new ArrayList<>();
        Matrix4f[] place = new Matrix4f[entries.length];
        Matrix3f[] turn = new Matrix3f[entries.length];
        for (int b = 0; b < entries.length; b++) {
            place[b] = new Matrix4f(rig.bones.get(b).rest()).rotateY((float) Math.PI);
            turn[b] = place[b].normal(new Matrix3f());
        }
        float[] w = new float[rig.bones.size()];
        Vector3f p = new Vector3f(), n = new Vector3f();
        int vertex = 0, maxQuads = 0;
        for (Map.Entry<RenderType, List<Src>> group : byType.entrySet()) {
            int first = vertex, count = 0;
            for (Src s : group.getValue()) {
                ByteBuffer d = s.data;
                int base = s.part.firstVertex() * SRC_SIZE;
                for (int v = 0; v < s.part.quadCount() * 4; v++) {
                    int at = base + v * SRC_SIZE;
                    place[s.bone].transformPosition(d.getFloat(at), d.getFloat(at + 4), d.getFloat(at + 8), p);
                    int color = d.getInt(at + 12);
                    float u = d.getFloat(at + 16), vv = d.getFloat(at + 20);
                    n.set(d.get(at + 24) / 127.0F, d.get(at + 25) / 127.0F, d.get(at + 26) / 127.0F);
                    turn[s.bone].transform(n);
                    if (n.lengthSquared() > 1.0E-8F) {
                        n.normalize();
                    }
                    int extra = d.getInt(at + 28);
                    out.putFloat(p.x).putFloat(p.y).putFloat(p.z).putInt(color).putFloat(u).putFloat(vv);
                    out.put(snorm(n.x)).put(snorm(n.y)).put(snorm(n.z)).put((byte) 0).putInt(extra);
                    weights(rig, s.bone, p, w);
                    writeWeights(out, w);
                    vertex++;
                }
                count += s.part.quadCount();
            }
            parts.add(new Part(group.getKey(), null, true, first, count, false, false));
            maxQuads = Math.max(maxQuads, count);
        }
        out.flip();
        return new RigGeometry(parts, out, null, vertices, maxQuads, rig);
    }

    private static final ThreadLocal<Matrix4f> INV = ThreadLocal.withInitial(Matrix4f::new);

    private static Matrix4f this_inv() {
        return INV.get();
    }

    /** Bounding sphere and farthest vertex per joint, each vertex counted for the joint with its largest weight. */
    private static void measure(RigDef rig, ByteBuffer data, int vertexCount, float[] bounds, float[] far) {
        int nj = rig.joints.size();
        float[] min = new float[nj * 3], max = new float[nj * 3];
        java.util.Arrays.fill(min, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(max, Float.NEGATIVE_INFINITY);
        java.util.Arrays.fill(far, Float.NaN);
        float[] farDist = new float[nj];
        Vector3f origin = new Vector3f();
        ByteBuffer d = data.duplicate().order(ByteOrder.nativeOrder());
        int base = d.position();
        for (int v = 0; v < vertexCount; v++) {
            int at = base + v * VERTEX_SIZE;
            int best = 0, bestW = -1;
            for (int k = 0; k < 4; k++) {
                int w = d.get(at + 36 + k) & 255;
                if (w > bestW) {
                    bestW = w;
                    best = d.get(at + 32 + k) & 255;
                }
            }
            if (best >= nj) {
                continue;
            }
            float x = d.getFloat(at), y = d.getFloat(at + 4), z = d.getFloat(at + 8);
            min[best * 3] = Math.min(min[best * 3], x);
            min[best * 3 + 1] = Math.min(min[best * 3 + 1], y);
            min[best * 3 + 2] = Math.min(min[best * 3 + 2], z);
            max[best * 3] = Math.max(max[best * 3], x);
            max[best * 3 + 1] = Math.max(max[best * 3 + 1], y);
            max[best * 3 + 2] = Math.max(max[best * 3 + 2], z);
            // the joint's position in the mesh's (bind) space
            rig.joints.get(best).inverseBind.invert(this_inv()).getTranslation(origin);
            float dist = origin.distanceSquared(x, y, z);
            if (dist > farDist[best]) {
                farDist[best] = dist;
                far[best * 3] = x;
                far[best * 3 + 1] = y;
                far[best * 3 + 2] = z;
            }
        }
        for (int j = 0; j < nj; j++) {
            if (min[j * 3] > max[j * 3]) {
                bounds[j * 4 + 3] = -1.0F;
                continue;
            }
            float cx = (min[j * 3] + max[j * 3]) * 0.5F, cy = (min[j * 3 + 1] + max[j * 3 + 1]) * 0.5F, cz = (min[j * 3 + 2] + max[j * 3 + 2]) * 0.5F;
            float dx = max[j * 3] - cx, dy = max[j * 3 + 1] - cy, dz = max[j * 3 + 2] - cz;
            bounds[j * 4] = cx;
            bounds[j * 4 + 1] = cy;
            bounds[j * 4 + 2] = cz;
            // a little slack: a blended vertex can move a bit beyond its main joint's sphere
            bounds[j * 4 + 3] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.15F;
        }
    }

    private static byte snorm(float f) {
        return (byte) Math.round(Math.clamp(f, -1.0F, 1.0F) * 127.0F);
    }

    /**
     * Weights of a vertex of bone "own" at p (rig space). Every blend that touches own moves part of its weight across
     * the joint: smoothstep along the joint axis over +-length, faded out beyond radius from the joint point (so a
     * torso's flank under a shoulder stays with the torso). A parent and a child vertex at the same place get the
     * same weights, so the two pieces meet without a seam.
     *
     * "half" (parts that overlap with rounded ends, like a thigh and a shin that both reach past the knee): each side
     * goes at most half way, reaching 50/50 at the joint plane; the ends past the plane stay 50/50 instead of swapping
     * to the other bone, so both caps turn half way and round the joint off instead of pinching it.
     */
    static void weights(RigDef rig, int own, Vector3f p, float[] w) {
        java.util.Arrays.fill(w, 0.0F);
        w[own] = 1.0F;
        for (RigDef.Blend bl : rig.blends) {
            boolean parent = bl.parent() == own, child = bl.child() == own;
            if (!parent && !child || parent && bl.side() == RigDef.Blend.CHILD || child && bl.side() == RigDef.Blend.PARENT) {
                continue;
            }
            float dx = p.x - bl.at().x, dy = p.y - bl.at().y, dz = p.z - bl.at().z;
            float d = dx * bl.axis().x + dy * bl.axis().y + dz * bl.axis().z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float g = 1.0F - smoothstep(bl.radius(), bl.radius() * 1.5F, dist);
            if (g <= 0.0F) {
                continue;
            }
            float t;
            if (bl.half()) {
                t = parent ? 0.5F * smoothstep(-bl.length(), 0.0F, d) : 0.5F + 0.5F * smoothstep(0.0F, bl.length(), d);
            } else {
                t = smoothstep(-bl.length(), bl.length(), d);
            }
            if (parent) {
                float move = w[own] * t * g;
                w[own] -= move;
                w[bl.child()] += move;
            } else {
                float move = w[own] * (1.0F - t) * g;
                w[own] -= move;
                w[bl.parent()] += move;
            }
        }
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** The four largest weights as bytes summing to 255. */
    static void writeWeights(ByteBuffer out, float[] w) {
        int[] idx = {0, 0, 0, 0};
        float[] val = {0, 0, 0, 0};
        for (int i = 0; i < w.length; i++) {
            float x = w[i];
            if (x <= 0.0F) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                if (x > val[k]) {
                    for (int m = 3; m > k; m--) {
                        val[m] = val[m - 1];
                        idx[m] = idx[m - 1];
                    }
                    val[k] = x;
                    idx[k] = i;
                    break;
                }
            }
        }
        float sum = val[0] + val[1] + val[2] + val[3];
        int[] q = new int[4];
        int total = 0;
        for (int k = 0; k < 4; k++) {
            q[k] = sum > 0 ? Math.round(val[k] / sum * 255.0F) : (k == 0 ? 255 : 0);
            total += q[k];
        }
        q[0] += 255 - total;
        out.put((byte) idx[0]).put((byte) idx[1]).put((byte) idx[2]).put((byte) idx[3]);
        out.put((byte) q[0]).put((byte) q[1]).put((byte) q[2]).put((byte) q[3]);
    }

    // ---------------------------------------------------------------- mesh file

    static RigGeometry fromFile(RigDef rig, RigDef.MeshSpec spec, ByteBuffer file, List<RigTexture> textures) {
        int vbytes = spec.vertexCount() * VERTEX_SIZE;
        ByteBuffer v = MemoryUtil.memAlloc(vbytes).order(ByteOrder.nativeOrder());
        ByteBuffer src = file.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        src.position((int) spec.vertexOffset()).limit((int) spec.vertexOffset() + vbytes);
        // the file is little endian, the GPU buffer native (both little endian on every platform Minecraft runs on)
        v.put(src);
        v.flip();
        ByteBuffer i = MemoryUtil.memAlloc(spec.indexCount() * 4).order(ByteOrder.nativeOrder());
        ByteBuffer isrc = file.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        isrc.position((int) spec.indexOffset()).limit((int) spec.indexOffset() + spec.indexCount() * 4);
        i.put(isrc);
        i.flip();
        List<Part> parts = new ArrayList<>();
        for (int k = 0; k < spec.submeshes().size(); k++) {
            RigDef.Submesh s = spec.submeshes().get(k);
            parts.add(new Part(null, textures.get(k), false, s.firstIndex(), s.indexCount(), s.doubleSided(), s.linear()));
        }
        return new RigGeometry(parts, v, i, spec.vertexCount(), 0, rig);
    }
}
