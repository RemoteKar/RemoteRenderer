package kr.gcb.remoterenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.objmc.ObjmcModel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The two pipelines. They are compiled from this jar's own shader files through a private ShaderSource, so
 * no resource pack (a server's included) can replace or break them, and Fabric API is not needed for them.
 */
public final class MeshPipelines {
    /** Static model vertices: 32 bytes. */
    public static final VertexFormat MESH_VERTEX = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("Extra", GpuFormat.RGBA8_UNORM)
        .build();
    /** Per instance: pose (64) + normal matrix (36) + light/overlay (8) = 108 bytes. */
    public static final VertexFormat MESH_INSTANCE = VertexFormat.builder(1)
        .addAttribute("IModel", GpuFormat.RGBA32_FLOAT, 4)
        .addAttribute("INormal", GpuFormat.RGB32_FLOAT, 3)
        .addAttribute("ILight", GpuFormat.RGBA16_SINT)
        .build();
    /** objmc: only the vertex id, everything else comes from the data texture. */
    public static final VertexFormat OBJMC_VERTEX = VertexFormat.builder(0)
        .addAttribute("VertexId", GpuFormat.R32_SINT)
        .build();
    /** Per instance: placement (64) + animation (16 + 16) + overlay colour (4) + light (8) = 108 bytes. */
    public static final VertexFormat OBJMC_INSTANCE = VertexFormat.builder(1)
        .addAttribute("IModel", GpuFormat.RGBA32_FLOAT, 4)
        .addAttribute("IObj", GpuFormat.RGBA32_FLOAT)
        .addAttribute("IObj2", GpuFormat.RGBA32_SINT)
        .addAttribute("IOverlay", GpuFormat.RGBA8_UNORM)
        .addAttribute("ILight", GpuFormat.RGBA16_SINT)
        .build();

    /** Rig vertices: the mesh vertex plus 4 joint indices and 4 weights = 40 bytes (see RigGeometry). */
    public static final VertexFormat RIG_VERTEX = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("Extra", GpuFormat.RGBA8_UNORM)
        .addAttribute("Joints", GpuFormat.RGBA8_UINT)
        .addAttribute("Weights", GpuFormat.RGBA8_UNORM)
        .build();
    /** Per rig instance: first joint, light, tint, unused = 16 bytes. */
    public static final VertexFormat RIG_INSTANCE = VertexFormat.builder(1)
        .addAttribute("IRig", GpuFormat.RGBA32_SINT)
        .build();
    private static final BindGroupLayout RIG_JOINTS = BindGroupLayout.builder()
        .withUniform("RigJoints", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();

    private static final BindGroupLayout OBJMC_DATA = BindGroupLayout.builder().withUniform("ObjmcData", UniformType.COMBINED_IMAGE_SAMPLER).build();

    public static final RenderPipeline MESH = RenderPipeline.builder()
        .withLocation(id("pipeline/mesh"))
        .withVertexShader(id("core/mesh"))
        .withFragmentShader(id("core/mesh"))
        .withShaderDefine("ALPHA_CUTOUT", 0.1F)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
        .withVertexBinding(0, MESH_VERTEX)
        .withVertexBinding(1, MESH_INSTANCE)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();

    public static final RenderPipeline OBJMC = RenderPipeline.builder()
        .withLocation(id("pipeline/objmc"))
        .withVertexShader(id("core/objmc"))
        .withFragmentShader(id("core/objmc"))
        .withShaderDefine("DATA_WIDTH", ObjmcModel.DATA_WIDTH)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withBindGroupLayout(OBJMC_DATA)
        .withVertexBinding(0, OBJMC_VERTEX)
        .withVertexBinding(1, OBJMC_INSTANCE)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();

    public static final RenderPipeline RIG = rig("pipeline/rig", true);
    public static final RenderPipeline RIG_DOUBLE = rig("pipeline/rig_double", false);

    private static RenderPipeline rig(String location, boolean cull) {
        return RenderPipeline.builder()
            .withLocation(id(location))
            .withVertexShader(id("core/rig"))
            .withFragmentShader(id("core/rig"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(RIG_JOINTS)
            .withVertexBinding(0, RIG_VERTEX)
            .withVertexBinding(1, RIG_INSTANCE)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(cull)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();
    }

    private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> FAILED = new IdentityHashMap<>();
    private static @Nullable GpuDevice compiledFor;

    private MeshPipelines() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("remoterenderer", path);
    }

    /** Both pipelines, now (render thread; called when the game's own shaders are (re)loaded). */
    public static void precompile() {
        try {
            long t0 = System.nanoTime();
            get(MESH);
            get(OBJMC);
            get(RIG);
            get(RIG_DOUBLE);
            RemoteRenderer.LOG.info("RemoteRenderer pipelines ready ({} ms)", (System.nanoTime() - t0) / 1_000_000);
        } catch (RuntimeException e) {
            RemoteRenderer.LOG.error("RemoteRenderer pipeline precompile failed", e);
        }
    }

    /** The compiled pipeline, compiling it on first use; null if it does not compile on this device. */
    public static @Nullable CompiledRenderPipeline get(RenderPipeline pipeline) {
        GpuDevice device = RenderSystem.getDevice();
        if (device != compiledFor) {
            COMPILED.values().forEach(CompiledRenderPipeline::close);
            COMPILED.clear();
            FAILED.clear();
            compiledFor = device;
        }
        CompiledRenderPipeline compiled = COMPILED.get(pipeline);
        if (compiled != null && !compiled.isClosed()) {
            return compiled;
        }
        if (FAILED.containsKey(pipeline)) {
            return null;
        }
        try {
            compiled = device.compilePipeline(pipeline, SOURCE, Util.backgroundExecutor()).join().finishCompile();
        } catch (RuntimeException e) {
            RemoteRenderer.LOG.error("RemoteRenderer pipeline {} failed to compile", pipeline.getLocation(), e);
            compiled = null;
        }
        if (compiled == null) {
            FAILED.put(pipeline, Boolean.TRUE);
            return null;
        }
        COMPILED.put(pipeline, compiled);
        RemoteRenderer.LOG.info("RemoteRenderer pipeline {} compiled", pipeline.getLocation());
        return compiled;
    }

    private static final ShaderSource SOURCE = new ShaderSource() {
        @Override
        public @Nullable String getShader(Identifier id, ShaderType type) {
            String path = "/assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
            try (InputStream in = MeshPipelines.class.getResourceAsStream(path)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    };
}
