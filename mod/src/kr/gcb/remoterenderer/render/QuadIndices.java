package kr.gcb.remoterenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** A static index buffer turning quads into triangles (0 1 2, 2 3 0 - the vanilla quad order). */
public final class QuadIndices {
    private static @Nullable GpuBuffer buffer;
    private static int quads;

    private QuadIndices() {
    }

    /** Prepare phase only. */
    public static GpuBuffer ensure(int neededQuads) {
        if (buffer == null || buffer.isClosed() || quads < neededQuads) {
            int n = Math.max(Math.max(neededQuads, quads * 2), 16384);
            ByteBuffer data = MemoryUtil.memAlloc(n * 6 * 4).order(ByteOrder.nativeOrder());
            for (int q = 0; q < n; q++) {
                int v = q * 4;
                data.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
            }
            data.flip();
            if (buffer != null) {
                buffer.close();
            }
            final int count = n;
            buffer = RenderSystem.getDevice().createBuffer(() -> "RemoteRenderer quad indices x" + count, GpuBuffer.USAGE_INDEX, data);
            MemoryUtil.memFree(data);
            quads = n;
        }
        return buffer;
    }
}
