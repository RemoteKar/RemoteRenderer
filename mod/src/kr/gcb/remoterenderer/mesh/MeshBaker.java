package kr.gcb.remoterenderer.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.objmc.ObjmcDecoder;
import kr.gcb.remoterenderer.objmc.ObjmcModel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns a MeshKey into vertex data. Each vertex is what VertexConsumer.putBakedQuad would write for the quad,
 * minus the per-entity parts: position and normal go through the layer's own transform only (the entity's
 * pose is applied on the GPU), the colour is the layer tint, the light emission of the quad is kept so the
 * shader can raise the entity's light the way LightCoordsUtil.lightCoordsWithEmission does.
 */
final class MeshBaker {
    /** Position RGB32F, Color RGBA8, UV0 RG32F, Normal RGBA8 snorm, Extra RGBA8 (x = emission). */
    static final int VERTEX_SIZE = 32;

    private MeshBaker() {
    }

    private record QuadRef(BakedQuad quad, Matrix4f pose, Matrix3f normal, int color) {
    }

    private static final class ObjmcBuild {
        final ObjmcModel model;
        final RenderType renderType;
        final TextureAtlasSprite sprite;
        final Matrix4f pose;
        final int layerIndex;
        final List<BakedQuad> quads = new ArrayList<>();

        ObjmcBuild(ObjmcModel model, RenderType renderType, TextureAtlasSprite sprite, Matrix4f pose, int layerIndex) {
            this.model = model;
            this.renderType = renderType;
            this.sprite = sprite;
            this.pose = pose;
            this.layerIndex = layerIndex;
        }
    }

    /** null: something in it cannot take the GPU path (the vanilla renderer draws it). */
    static @Nullable MeshEntry bake(MeshKey key, ItemDisplayContext context) {
        Map<RenderType, List<QuadRef>> solid = new LinkedHashMap<>();
        List<ObjmcBuild> objmc = new ArrayList<>();
        List<MeshEntry.TranslucentLayer> translucent = new ArrayList<>();
        PoseStack.Pose pose = new PoseStack.Pose();
        int solidQuads = 0;

        for (int li = 0; li < key.layers.length; li++) {
            MeshKey.Layer layer = key.layers[li];
            pose.setIdentity();
            layer.transform.apply(layer.leftHand, pose);
            pose.mulPose(new Matrix4f().set(layer.local));
            Matrix4f m = new Matrix4f(pose.pose());
            Matrix3f n = new Matrix3f(pose.normal());
            ItemQuads quads = layer.quads;

            if (layer.objmc) {
                Map<TextureAtlasSprite, ObjmcBuild> bySprite = new LinkedHashMap<>();
                for (BakedQuad quad : quads.all()) {
                    TextureAtlasSprite sprite = quad.materialInfo().sprite();
                    ObjmcBuild b = bySprite.get(sprite);
                    if (b == null) {
                        ObjmcModel model = ObjmcDecoder.get(sprite);
                        if (model == null) {
                            return null;
                        }
                        b = new ObjmcBuild(model, quad.materialInfo().itemRenderType(), sprite, m, li);
                        bySprite.put(sprite, b);
                        objmc.add(b);
                    }
                    b.quads.add(quad);
                }
                continue;
            }

            int[] tints = layer.tints == null ? EntityCache.NO_TINTS : layer.tints;
            for (BakedQuad quad : quads.solid()) {
                BakedQuad.MaterialInfo material = quad.materialInfo();
                RenderType renderType = material.itemRenderType();
                if (renderType.pipeline() != RenderPipelines.ITEM_CUTOUT) {
                    return null;
                }
                int color = material.isTinted() && material.tintIndex() >= 0 && material.tintIndex() < tints.length ? tints[material.tintIndex()] : -1;
                solid.computeIfAbsent(renderType, rt -> new ArrayList<>()).add(new QuadRef(quad, m, n, color));
                solidQuads++;
            }
            if (!quads.translucent().isEmpty()) {
                translucent.add(new MeshEntry.TranslucentLayer(m, tints, new ItemQuads(quads.translucent(), List.of(), quads.translucent())));
            }
        }

        // solid quads -> one vertex buffer, a part per render type
        List<MeshEntry.Part> parts = new ArrayList<>();
        ByteBuffer vertexData = null;
        if (solidQuads > 0) {
            vertexData = MemoryUtil.memAlloc(solidQuads * 4 * VERTEX_SIZE).order(ByteOrder.nativeOrder());
            Vector3f p = new Vector3f();
            Vector3f nv = new Vector3f();
            int vertex = 0;
            for (Map.Entry<RenderType, List<QuadRef>> e : solid.entrySet()) {
                int first = vertex;
                for (QuadRef ref : e.getValue()) {
                    BakedQuad quad = ref.quad;
                    Vector3fc dir = quad.direction().getUnitVec3f();
                    ref.normal.transform(dir.x(), dir.y(), dir.z(), nv).normalize();
                    byte nx = snorm(nv.x), ny = snorm(nv.y), nz = snorm(nv.z);
                    int c = ref.color;
                    byte cr = (byte) (c >> 16), cg = (byte) (c >> 8), cb = (byte) c, ca = (byte) (c >>> 24);
                    byte emission = (byte) Math.clamp(quad.materialInfo().lightEmission(), 0, 15);
                    for (int v = 0; v < 4; v++) {
                        ref.pose.transformPosition(quad.position(v), p);
                        long uv = quad.packedUV(v);
                        vertexData.putFloat(p.x).putFloat(p.y).putFloat(p.z);
                        vertexData.put(cr).put(cg).put(cb).put(ca);
                        vertexData.putFloat(UVPair.unpackU(uv)).putFloat(UVPair.unpackV(uv));
                        vertexData.put(nx).put(ny).put(nz).put((byte) 0);
                        vertexData.put(emission).put((byte) 0).put((byte) 0).put((byte) 0);
                    }
                    vertex += 4;
                }
                parts.add(new MeshEntry.Part(e.getKey(), first, (vertex - first) / 4));
            }
            vertexData.flip();
        }

        // objmc dummy faces -> vertex ids
        List<MeshEntry.ObjmcPart> objmcParts = new ArrayList<>();
        ByteBuffer idData = null;
        if (!objmc.isEmpty()) {
            int total = 0;
            for (ObjmcBuild b : objmc) {
                total += b.quads.size();
            }
            idData = MemoryUtil.memAlloc(total * 4 * 4).order(ByteOrder.nativeOrder());
            int vertex = 0;
            for (ObjmcBuild b : objmc) {
                int first = vertex;
                Vector3f[] corners = null;
                int tintIndex = -1;
                float u0 = b.sprite.getU0(), du = b.sprite.getU1() - u0, v0 = b.sprite.getV0(), dv = b.sprite.getV1() - v0;
                int w = b.sprite.contents().width(), h = b.sprite.contents().height();
                int skipped = 0;
                for (BakedQuad quad : b.quads) {
                    float uc = 0.0F, vc = 0.0F;
                    for (int v = 0; v < 4; v++) {
                        uc += UVPair.unpackU(quad.packedUV(v));
                        vc += UVPair.unpackV(quad.packedUV(v));
                    }
                    int px = (int) Math.floor((uc / 4.0F - u0) / du * w);
                    int py = (int) Math.floor((vc / 4.0F - v0) / dv * h);
                    int face = b.model.faceId(px, py);
                    if (face < 0) {
                        skipped++;
                        continue;
                    }
                    for (int v = 0; v < 4; v++) {
                        // objmc: vertex id = face * 4 + gl_VertexID % 4, the corner in the order the quad is emitted
                        idData.putInt(face * 4 + v);
                    }
                    vertex += 4;
                    if (corners == null) {
                        corners = new Vector3f[4];
                        for (int v = 0; v < 4; v++) {
                            corners[v] = b.pose.transformPosition(quad.position(v), new Vector3f());
                        }
                        tintIndex = quad.materialInfo().isTinted() ? quad.materialInfo().tintIndex() : -1;
                    }
                }
                if (skipped > 0) {
                    RemoteRenderer.LOG.warn("objmc model {}: {} of {} faces point outside the uv header", b.model.name, skipped, b.quads.size());
                }
                if (corners != null) {
                    objmcParts.add(new MeshEntry.ObjmcPart(b.model, b.renderType, first, (vertex - first) / 4, corners, tintIndex, b.layerIndex));
                }
            }
            idData.flip();
        }

        if (parts.isEmpty() && objmcParts.isEmpty() && translucent.isEmpty()) {
            if (vertexData != null) {
                MemoryUtil.memFree(vertexData);
            }
            if (idData != null) {
                MemoryUtil.memFree(idData);
            }
            return null;
        }
        return new MeshEntry(context, List.copyOf(parts), List.copyOf(objmcParts), List.copyOf(translucent), solidQuads, vertexData, idData);
    }

    /** BufferBuilder.normalIntValue */
    private static byte snorm(float f) {
        return (byte) ((int) (Math.clamp(f, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }
}
