package kr.gcb.remoterenderer.rig;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import kr.gcb.remoterenderer.RemoteRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A rig texture: a plain PNG from a resource pack. It is not stitched into an atlas (textures/rig/... is no atlas
 * source), so a 4096 x 4096 texture neither grows the block / item atlas nor is loaded with it:
 *  - it is decoded (and mipped, and scaled down to rigTextureMaxSize) on a background thread the first time a model
 *    using it is about to be drawn - until then that model is not drawn, for a fraction of a second;
 *  - its GPU memory is given back when no model used it for rigTextureIdleSeconds; it is loaded again when needed.
 */
public final class RigTexture {
    public final Identifier id;
    private volatile NativeImage @Nullable [] levels;
    private volatile boolean loading;
    private volatile boolean failed;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private long lastUsed;
    private boolean closed;

    RigTexture(Identifier id) {
        this.id = id;
    }

    /**
     * Render thread, prepare phase: the GPU texture if it is there (uploading a finished decode now), else null and the
     * decode is started.
     */
    public @Nullable GpuTextureView acquire(long now) {
        this.lastUsed = now;
        if (this.closed) {
            return null;
        }
        if (this.view != null) {
            return this.view;
        }
        NativeImage[] lv = this.levels;
        if (lv != null) {
            this.levels = null;
            GpuDevice device = RenderSystem.getDevice();
            this.texture = device.createTexture(() -> "RemoteRenderer rig " + this.id, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, lv[0].getWidth(), lv[0].getHeight(), 1, lv.length);
            var encoder = device.createCommandEncoder();
            for (int i = 0; i < lv.length; i++) {
                encoder.writeToTexture(this.texture, lv[i], i, 0, 0, 0);
                lv[i].close();
            }
            this.view = device.createTextureView(this.texture);
            return this.view;
        }
        if (!this.loading && !this.failed) {
            this.loading = true;
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            int max = RemoteRenderer.config.rigTextureMaxSize;
            CompletableFuture.runAsync(() -> {
                try {
                    long t0 = System.nanoTime();
                    NativeImage[] decoded = decode(resources, this.id, max);
                    if (this.closed) {
                        for (NativeImage i : decoded) {
                            i.close();
                        }
                    } else {
                        this.levels = decoded;
                        RemoteRenderer.LOG.info("Rig texture {} loaded: {}x{}, {} mip levels ({} ms)", this.id, decoded[0].getWidth(), decoded[0].getHeight(),
                            decoded.length, (System.nanoTime() - t0) / 1_000_000);
                    }
                } catch (IOException | RuntimeException e) {
                    this.failed = true;
                    RemoteRenderer.LOG.warn("Rig texture {} not loaded: {}", this.id, e.toString());
                } finally {
                    this.loading = false;
                }
            }, Util.backgroundExecutor());
        }
        return null;
    }

    private net.minecraft.resources.@Nullable Identifier mcId;
    private static int nextMcId;

    /**
     * The texture under an id of the vanilla texture manager (for entity render types, which a shader pack draws with
     * its own programs); null while it is not on the GPU. Render thread.
     */
    public net.minecraft.resources.@Nullable Identifier minecraftId(long now) {
        GpuTextureView v = this.acquire(now);
        if (v == null || this.texture == null) {
            return null;
        }
        if (this.mcId == null) {
            this.mcId = net.minecraft.resources.Identifier.fromNamespaceAndPath("remoterenderer", "rig/" + nextMcId++);
            McTexture wrapper = new McTexture(this.texture, v);
            Minecraft.getInstance().getTextureManager().register(this.mcId, wrapper);
        }
        return this.mcId;
    }

    private void releaseMcId() {
        if (this.mcId != null) {
            Minecraft.getInstance().getTextureManager().release(this.mcId);
            this.mcId = null;
        }
    }

    /** Hands our GPU texture to vanilla without giving it away: closing the wrapper leaves the texture alone. */
    private static final class McTexture extends net.minecraft.client.renderer.texture.AbstractTexture {
        McTexture(GpuTexture texture, GpuTextureView view) {
            this.texture = texture;
            this.textureView = view;
            this.sampler = RenderSystem.getSamplerCache().getSampler(com.mojang.renderpearl.api.textures.AddressMode.REPEAT,
                com.mojang.renderpearl.api.textures.AddressMode.REPEAT, com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                com.mojang.renderpearl.api.textures.FilterMode.LINEAR, true);
        }

        @Override
        public void close() {
            this.texture = null;
            this.textureView = null;
        }

        @Override
        protected void releaseTextures() {
            this.texture = null;
            this.textureView = null;
        }
    }

    /** The view uploaded by acquire this frame (execute phase). */
    public @Nullable GpuTextureView view() {
        return this.view;
    }

    public boolean failed() {
        return this.failed;
    }

    /** Bytes on the GPU (with mips), 0 when not resident. */
    public long gpuBytes() {
        if (this.texture == null) {
            return 0L;
        }
        long w = this.texture.getWidth(0), h = this.texture.getHeight(0);
        return w * h * 4 * 4 / 3;
    }

    /** Frees the GPU texture if no model used it for idleNanos (render thread, outside a pass). */
    void evictIfIdle(long now, long idleNanos) {
        if (this.view != null && now - this.lastUsed > idleNanos) {
            this.releaseMcId();
            RemoteRenderer.LOG.info("Rig texture {} {}: GPU memory freed", this.id, idleNanos == 0L ? "dropped" : "unused for " + idleNanos / 1_000_000_000L + " s");
            this.view.close();
            this.view = null;
            if (this.texture != null) {
                this.texture.close();
                this.texture = null;
            }
        }
    }

    /** Decodes, scales down to maxSize (0 = keep) and mips (any thread). */
    private static NativeImage[] decode(ResourceManager resources, Identifier id, int maxSize) throws IOException {
        NativeImage base;
        try (InputStream in = resources.open(id)) {
            base = NativeImage.read(in);
        }
        while (maxSize > 0 && Math.max(base.getWidth(), base.getHeight()) > maxSize && Math.max(base.getWidth(), base.getHeight()) > 1) {
            NativeImage smaller = half(base);
            base.close();
            base = smaller;
        }
        int n = 1;
        for (int w = base.getWidth(), h = base.getHeight(); w > 1 || h > 1; w = Math.max(1, w >> 1), h = Math.max(1, h >> 1)) {
            n++;
        }
        NativeImage[] levels = new NativeImage[n];
        levels[0] = base;
        for (int i = 1; i < n; i++) {
            levels[i] = half(levels[i - 1]);
        }
        return levels;
    }

    /** 2x2 box filter, colour weighted by alpha so cut-out edges do not darken. */
    private static NativeImage half(NativeImage src) {
        int sw = src.getWidth(), sh = src.getHeight();
        int w = Math.max(1, sw >> 1), h = Math.max(1, sh >> 1);
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = src.getPixel(Math.min(sw - 1, x * 2 + dx), Math.min(sh - 1, y * 2 + dy));
                        int pa = p >>> 24 & 255;
                        a += pa;
                        r += (long) (p >>> 16 & 255) * pa;
                        g += (long) (p >>> 8 & 255) * pa;
                        b += (long) (p & 255) * pa;
                    }
                }
                int outA = (int) (a / 4);
                int outR = a == 0 ? 0 : (int) (r / a), outG = a == 0 ? 0 : (int) (g / a), outB = a == 0 ? 0 : (int) (b / a);
                dst.setPixel(x, y, outA << 24 | outR << 16 | outG << 8 | outB);
            }
        }
        return dst;
    }

    void close() {
        this.closed = true;
        this.releaseMcId();
        NativeImage[] lv = this.levels;
        this.levels = null;
        if (lv != null) {
            for (NativeImage i : lv) {
                i.close();
            }
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
}
