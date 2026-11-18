package kr.gcb.remoterenderer.objmc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * An objmc texture, decoded. The vertex data is flattened into one RGBA32F texture: for vertex v of frame f
 * (t = f * vertexCount + v) texel 2t holds the position in blocks and texel 2t+1 the atlas uv.
 */
public final class ObjmcModel {
    public static final int DATA_WIDTH = 4096;

    public final int width;          // objmc "size.x" = texture width
    public final int vertexCount;    // faces * 4
    public final int frames;
    public final int duration;       // ticks
    public final boolean autoplay;
    public final int easing;         // 0 none, 1 linear, 2 in-out cubic, 3 bezier
    public final int colorBehavior;
    public final boolean noShadow;
    public final boolean autoRotate;
    public final boolean visibleInWorld;
    private final int[] faceHeader;  // ARGB of the uv header rows (rows 1 .. headerHeight-1)
    public final int headerHeight;
    public final int dataHeight;
    public final String name;

    private @Nullable ByteBuffer data;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private boolean closed;

    ObjmcModel(String name, int width, int vertexCount, int frames, int duration, boolean autoplay, int easing, int colorBehavior, boolean noShadow,
               boolean autoRotate, boolean visibleInWorld, int headerHeight, int[] faceHeader, ByteBuffer data, int dataHeight) {
        this.name = name;
        this.width = width;
        this.vertexCount = vertexCount;
        this.frames = frames;
        this.duration = duration;
        this.autoplay = autoplay;
        this.easing = easing;
        this.colorBehavior = colorBehavior;
        this.noShadow = noShadow;
        this.autoRotate = autoRotate;
        this.visibleInWorld = visibleInWorld;
        this.headerHeight = headerHeight;
        this.faceHeader = faceHeader;
        this.data = data;
        this.dataHeight = dataHeight;
    }

    /**
     * The face a dummy quad stands for, from the header pixel its uv points at (sprite pixel x, y with y >= 1);
     * the pixel stores its own offset from the texture's top left, as the objmc shader reads it. -1 if invalid.
     */
    public int faceId(int px, int py) {
        if (py < 1 || py >= this.headerHeight || px < 0 || px >= this.width) {
            return -1;
        }
        int argb = this.faceHeader[(py - 1) * this.width + px];
        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255, a = argb >>> 24;
        int ox = r * 256 + g, oy = b * 256 + a;
        int face = (oy - 1) * this.width + ox;
        return face >= 0 && face * 4 < this.vertexCount ? face : -1;
    }

    /** Prepare phase only. */
    public @Nullable GpuTextureView ensureUploaded() {
        if (this.closed) {
            return null;
        }
        if (this.view == null && this.data != null) {
            GpuDevice device = RenderSystem.getDevice();
            ByteBuffer d = this.data;
            this.data = null;
            this.texture = device.createTexture(() -> "RemoteRenderer objmc " + this.name, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA32_FLOAT, DATA_WIDTH, this.dataHeight, 1, 1);
            device.createCommandEncoder().writeToTexture(this.texture, d, 0, 0, 0, 0, DATA_WIDTH, this.dataHeight);
            MemoryUtil.memFree(d);
            this.view = device.createTextureView(this.texture);
        }
        return this.view;
    }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.data != null) {
            MemoryUtil.memFree(this.data);
            this.data = null;
        }
        if (this.view != null) {
            this.view.close();
            this.view = null;
        }
        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }
    }

    public boolean isClosed() {
        return this.closed;
    }
}
