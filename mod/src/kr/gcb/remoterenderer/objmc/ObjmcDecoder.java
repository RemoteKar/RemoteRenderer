package kr.gcb.remoterenderer.objmc;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.mixin.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Reads objmc's texture format (https://github.com/Godlander/objmc, objmc.py + objmc_main.glsl, 2026 version):
 * pixel (0,0) = marker (12,34,56,78), or alpha 79 with "compression"; header pixels 1..6 on row 0; one uv header
 * pixel per face below that; then the textures, the position table (3 px each, 24 bit fixed point + 128 offset),
 * the uv table (2 px each, 16 bit) and the vertex table (position index, uv index per vertex and frame).
 */
public final class ObjmcDecoder {
    private static final Object NOT_OBJMC = new Object();
    // read by the render thread, filled by bakes on background workers
    private static final Map<TextureAtlasSprite, Object> CACHE = new ConcurrentHashMap<>();
    private static final List<ObjmcModel> LIVE = new ArrayList<>();

    private ObjmcDecoder() {
    }

    /** The decoded model if the sprite is an objmc texture, null otherwise. Any thread (decodes on first use). */
    public static @Nullable ObjmcModel get(TextureAtlasSprite sprite) {
        Object cached = CACHE.get(sprite);
        if (cached == null) {
            cached = CACHE.computeIfAbsent(sprite, ObjmcDecoder::decodeOrMark);
        }
        return cached instanceof ObjmcModel m ? m : null;
    }

    private static Object decodeOrMark(TextureAtlasSprite sprite) {
        Object cached;
        {
            ObjmcModel model = null;
            try {
                model = decode(sprite);
            } catch (RuntimeException e) {
                RemoteRenderer.LOG.warn("objmc texture {} could not be decoded: {}", sprite.contents().name(), e.toString());
            }
            cached = model == null ? NOT_OBJMC : model;
            if (model != null) {
                synchronized (LIVE) {
                    LIVE.add(model);
                }
                RemoteRenderer.LOG.info("objmc model {}: {} faces, {} frames, duration {}t, easing {}, colorbehavior {}, autorotate {}",
                    model.name, model.vertexCount / 4, model.frames, model.duration, model.easing, model.colorBehavior, model.autoRotate);
            }
        }
        return cached;
    }

    /** Cheap check without decoding: is pixel (0,0) the objmc marker? */
    public static boolean isObjmc(TextureAtlasSprite sprite) {
        Object cached = CACHE.get(sprite);
        if (cached != null) {
            return cached instanceof ObjmcModel;
        }
        NativeImage image = imageOf(sprite.contents());
        boolean marked = image != null && markerAlpha(image) != 0;
        if (!marked) {
            // most sprites are not objmc: remember that, the check runs for every layer of every new item
            CACHE.putIfAbsent(sprite, NOT_OBJMC);
        }
        return marked;
    }

    /** After a resource reload the sprites are new objects: forget the old ones and free their textures. */
    public static void clear() {
        synchronized (LIVE) {
            for (ObjmcModel m : LIVE) {
                m.close();
            }
            LIVE.clear();
        }
        CACHE.clear();
    }

    private static @Nullable NativeImage imageOf(SpriteContents contents) {
        try {
            NativeImage image = ((SpriteContentsAccessor) (Object) contents).remoterenderer$originalImage();
            return image != null && image.format() == NativeImage.Format.RGBA && image.getWidth() >= 8 ? image : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int markerAlpha(NativeImage image) {
        int p = image.getPixel(0, 0);
        int a = p >>> 24, r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
        return r == 12 && g == 34 && b == 56 && (a == 78 || a == 79) ? a : 0;
    }

    private static @Nullable ObjmcModel decode(TextureAtlasSprite sprite) {
        NativeImage img = imageOf(sprite.contents());
        if (img == null) {
            return null;
        }
        int marker = markerAlpha(img);
        if (marker == 0) {
            return null;
        }
        boolean compression = marker == 79;
        int imgW = img.getWidth(), imgH = img.getHeight();
        int[] t1 = rgba(img.getPixel(1, 0)), t2 = rgba(img.getPixel(2, 0)), t3 = rgba(img.getPixel(3, 0));
        int[] t4 = rgba(img.getPixel(4, 0)), t5 = rgba(img.getPixel(5, 0)), t6 = rgba(img.getPixel(6, 0));
        int sizeX = t1[0] * 256 + t1[1];
        int sizeY = t1[2] * 256 + t1[3];
        long nverticesL = ((long) t2[0] << 24) + (t2[1] << 16) + (t2[2] << 8) + t2[3];
        int nframes = Math.max(t3[0] * 65536 + t3[1] * 256 + t3[2], 1);
        int ntextures = Math.max(t3[3], 1);
        int duration = Math.max(t4[0] * 65536 + t4[1] * 256 + t4[2], 1);
        boolean autoplay = (t4[3] >> 6 & 1) != 0;
        int easing = t4[3] >> 4 & 3;
        int vph = t5[0] * 256 + t5[1];
        int vth = t5[2] * 256 + t5[3];
        boolean noShadow = (t6[0] >> 7 & 1) != 0;
        boolean autoRotate = (t6[0] >> 6 & 1) != 0 || (t6[0] >> 5 & 1) != 0;
        boolean world = (t6[0] >> 4 & 1) != 0;
        int colorBehavior = (t6[0] & 1) * 256 + t6[1];

        if (sizeX != imgW || nverticesL <= 0 || nverticesL % 4 != 0 || nverticesL > 64_000_000L) {
            throw new IllegalStateException("bad header: size " + sizeX + "x" + sizeY + " image " + imgW + "x" + imgH + " vertices " + nverticesL);
        }
        int nvertices = (int) nverticesL;
        int headerHeight = 1 + (int) Math.ceil(nvertices * 0.25 / sizeX);
        int height = headerHeight + sizeY * ntextures;
        int vertRow = height + vph + vth;
        long totalTexels = 2L * nframes * nvertices;
        if (totalTexels > (long) ObjmcModel.DATA_WIDTH * 16384L) {
            throw new IllegalStateException("too much data: " + nframes + " frames x " + nvertices + " vertices");
        }

        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        int dataHeight = (int) ((totalTexels + ObjmcModel.DATA_WIDTH - 1) / ObjmcModel.DATA_WIDTH);
        ByteBuffer data = MemoryUtil.memCalloc(ObjmcModel.DATA_WIDTH * dataHeight * 16).order(ByteOrder.nativeOrder());
        try {
            for (int frame = 0; frame < nframes; frame++) {
                for (int v = 0; v < nvertices; v++) {
                    int id = v + frame * nvertices;
                    int posIndex, uvIndex;
                    if (!compression) {
                        int i = id * 2;
                        posIndex = rgb24(px(img, i % sizeX, vertRow + i / sizeX));
                        uvIndex = rgb24(px(img, (i + 1) % sizeX, vertRow + (i + 1) / sizeX));
                    } else {
                        int p = px(img, id % sizeX, vertRow + id / sizeX);
                        posIndex = rgb24(p);
                        uvIndex = (p >>> 24) - 1;
                    }
                    long base = (long) id * 32;
                    int pi = posIndex * 3;
                    data.putFloat((int) base, coord(px(img, pi % sizeX, height + pi / sizeX)));
                    data.putFloat((int) base + 4, coord(px(img, (pi + 1) % sizeX, height + (pi + 1) / sizeX)));
                    data.putFloat((int) base + 8, coord(px(img, (pi + 2) % sizeX, height + (pi + 2) / sizeX)));
                    int ui = uvIndex * 2;
                    float u = uv16(px(img, ui % sizeX, height + vph + ui / sizeX));
                    float vv = uv16(px(img, (ui + 1) % sizeX, height + vph + (ui + 1) / sizeX));
                    // objmc: texCoord = (topleft + (0, headerheight) + uv * size) / atlasSize
                    data.putFloat((int) base + 16, u0 + (u * sizeX / imgW) * (u1 - u0));
                    data.putFloat((int) base + 20, v0 + ((headerHeight + vv * sizeY) / imgH) * (v1 - v0));
                }
            }
        } catch (RuntimeException e) {
            MemoryUtil.memFree(data);
            throw e;
        }
        int[] header = new int[sizeX * Math.max(headerHeight - 1, 0)];
        for (int y = 1; y < headerHeight; y++) {
            for (int x = 0; x < sizeX; x++) {
                header[(y - 1) * sizeX + x] = img.getPixel(x, y);
            }
        }
        return new ObjmcModel(sprite.contents().name().toString(), sizeX, nvertices, nframes, duration, autoplay, easing, colorBehavior,
            noShadow, autoRotate, world, headerHeight, header, data, dataHeight);
    }

    private static int px(NativeImage img, int x, int y) {
        if (y >= img.getHeight()) {
            throw new IllegalStateException("data runs past the image at row " + y);
        }
        return img.getPixel(x, y);
    }

    private static int[] rgba(int argb) {
        return new int[]{argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24};
    }

    private static int rgb24(int argb) {
        return argb & 0xFFFFFF;
    }

    /** objmc getpos: (r*65536 + g*256 + b) / 65536 - 128 blocks. */
    private static float coord(int argb) {
        return (argb & 0xFFFFFF) / 65536.0F - 128.0F;
    }

    /** objmc getuv: (g*256 + b) / 65535. */
    private static float uv16(int argb) {
        return (argb & 0xFFFF) / 65535.0F;
    }
}
