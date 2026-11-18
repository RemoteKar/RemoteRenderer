package kr.gcb.remoterenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.gcb.remoterenderer.RemoteRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Any server's item shader, instanced.
 *
 * A resource pack that replaces core/item.vsh / item.fsh (or the includes they use) draws its effects - materials,
 * normal maps, HUDs, decals, lighting markers - from the same per-vertex inputs vanilla gives every item quad: Position
 * (camera relative), Color (the tint), UV0, UV1 (overlay), UV2 (light) and Normal. So instead of stepping aside,
 * RemoteRenderer takes the pack's own vertex shader as it is, removes those input declarations, turns its main() into
 * an ordinary function and puts a small main in front that fills the same values from the baked model and the instance
 * (pose, normal matrix, light) - or, for rigs, from skinning. The fragment shader is the pack's, untouched, and the
 * pipeline copies vanilla's item pipeline (uniform blocks, samplers, defines, depth, blending). Whatever the pack
 * computes from those inputs comes out the same, one draw call for all displays of a model.
 *
 * If the pack's shader cannot be adapted (it does not compile this way), RemoteRenderer steps aside for that pack as
 * before, and the log says why.
 */
public final class PackShaders {
    /** The mesh vertex / instance under names that do not clash with the pack's globals (same layout as MeshPipelines). */
    static final VertexFormat VERTEX = VertexFormat.builder(0)
        .addAttribute("RrPosition", GpuFormat.RGB32_FLOAT)
        .addAttribute("RrColor", GpuFormat.RGBA8_UNORM)
        .addAttribute("RrUV0", GpuFormat.RG32_FLOAT)
        .addAttribute("RrNormal", GpuFormat.RGBA8_SNORM)
        .addAttribute("RrExtra", GpuFormat.RGBA8_UNORM)
        .build();
    static final VertexFormat INSTANCE = VertexFormat.builder(1)
        .addAttribute("RrModel", GpuFormat.RGBA32_FLOAT, 4)
        .addAttribute("RrNormalMat", GpuFormat.RGB32_FLOAT, 3)
        .addAttribute("RrLight", GpuFormat.RGBA16_SINT)
        .build();
    static final VertexFormat RIG_VERTEX = VertexFormat.builder(0)
        .addAttribute("RrPosition", GpuFormat.RGB32_FLOAT)
        .addAttribute("RrColor", GpuFormat.RGBA8_UNORM)
        .addAttribute("RrUV0", GpuFormat.RG32_FLOAT)
        .addAttribute("RrNormal", GpuFormat.RGBA8_SNORM)
        .addAttribute("RrExtra", GpuFormat.RGBA8_UNORM)
        .addAttribute("RrJoints", GpuFormat.RGBA8_UINT)
        .addAttribute("RrWeights", GpuFormat.RGBA8_UNORM)
        .build();
    static final VertexFormat RIG_INSTANCE = VertexFormat.builder(1)
        .addAttribute("RrRig", GpuFormat.RGBA32_SINT)
        .build();
    private static final BindGroupLayout RIG_JOINTS = BindGroupLayout.builder()
        .withUniform("RigJoints", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();

    private static final Identifier MESH_VS = Identifier.fromNamespaceAndPath("remoterenderer", "core/pack_mesh");
    private static final Identifier RIG_VS = Identifier.fromNamespaceAndPath("remoterenderer", "core/pack_rig");
    private static final String[] INPUTS = {"Position", "Color", "UV0", "UV1", "UV2", "UV3", "Normal"};
    private static final Pattern INPUT_DECL = Pattern.compile(
        "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+\\w+\\s+(Position|Color|UV0|UV1|UV2|UV3|Normal)\\s*;[ \\t]*$");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");

    private static @Nullable CompiledRenderPipeline mesh, rig, rigDouble;
    private static @Nullable String failure;
    private static boolean active;

    private PackShaders() {
    }

    /** The pack's item shader runs instanced (mesh and rig pipelines compiled from it). */
    public static boolean active() {
        return active;
    }

    public static @Nullable String failure() {
        return failure;
    }

    static @Nullable CompiledRenderPipeline mesh() {
        return active ? mesh : null;
    }

    static @Nullable CompiledRenderPipeline rig(boolean doubleSided) {
        return active ? (doubleSided ? rigDouble : rig) : null;
    }

    /** Render thread, after the game's shaders were (re)loaded. force: also for the vanilla item shader (tests). */
    public static void rebuild(ResourceManager resources, boolean custom, boolean force) {
        close();
        failure = null;
        if (!custom && !force) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            RenderPipeline base = RenderPipelines.ITEM_CUTOUT;
            Identifier vs = base.getShaders().get(ShaderType.VERTEX);
            Identifier fs = base.getShaders().get(ShaderType.FRAGMENT);
            String packVs = read(resources, vs, ShaderType.VERTEX);
            String meshSrc = adapt(packVs, MESH_WRAPPER_DECL, MESH_WRAPPER_MAIN);
            String rigSrc = adapt(packVs, RIG_WRAPPER_DECL, RIG_WRAPPER_MAIN);
            Map<Identifier, ShaderSource.CachedIncludeSource> includes = ShaderManager.listAllIncludes(resources);
            ShaderSource source = new ShaderSource() {
                @Override
                public @Nullable String getShader(Identifier id, ShaderType type) {
                    if (type == ShaderType.VERTEX && id.equals(MESH_VS)) {
                        return meshSrc;
                    }
                    if (type == ShaderType.VERTEX && id.equals(RIG_VS)) {
                        return rigSrc;
                    }
                    try {
                        return read(resources, id, type);
                    } catch (IOException e) {
                        return null;
                    }
                }

                @Override
                public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
                    return includes.get(id);
                }

                @Override
                public void close() {
                }
            };
            GpuDevice device = RenderSystem.getDevice();
            mesh = compile(device, source, clone(base, "pipeline/pack_mesh", MESH_VS, fs, VERTEX, INSTANCE, null, base.isCull()));
            rig = compile(device, source, clone(base, "pipeline/pack_rig", RIG_VS, fs, RIG_VERTEX, RIG_INSTANCE, RIG_JOINTS, base.isCull()));
            rigDouble = compile(device, source, clone(base, "pipeline/pack_rig_double", RIG_VS, fs, RIG_VERTEX, RIG_INSTANCE, RIG_JOINTS, false));
            for (var include : includes.values()) {
                include.close();
            }
            active = true;
            RemoteRenderer.LOG.info("RemoteRenderer runs the {} item shader instanced ({} ms)", custom ? "resource pack's" : "vanilla",
                (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            failure = e.getMessage() != null ? e.getMessage() : e.toString();
            RemoteRenderer.LOG.warn("The resource pack's item shader could not be run instanced, RemoteRenderer steps aside for it: {}", e.toString());
            close();
        }
    }

    private static void close() {
        for (CompiledRenderPipeline p : new CompiledRenderPipeline[]{mesh, rig, rigDouble}) {
            if (p != null) {
                p.close();
            }
        }
        mesh = rig = rigDouble = null;
        active = false;
    }

    private static CompiledRenderPipeline compile(GpuDevice device, ShaderSource source, RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = device.compilePipeline(pipeline, source, Util.backgroundExecutor()).join().finishCompile();
        if (compiled == null || compiled.isClosed()) {
            throw new IllegalStateException(pipeline.getLocation() + " did not compile (see the log above)");
        }
        return compiled;
    }

    /** Vanilla's item pipeline (uniform blocks, samplers, defines, depth, blending) with our vertex shader and buffers. */
    private static RenderPipeline clone(RenderPipeline base, String location, Identifier vs, Identifier fs, VertexFormat vertex, VertexFormat instance,
                                        @Nullable BindGroupLayout extra, boolean cull) {
        RenderPipeline.Builder b = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("remoterenderer", location))
            .withVertexShader(vs)
            .withFragmentShader(fs);
        for (Map.Entry<String, String> d : base.getShaderDefines().values().entrySet()) {
            String v = d.getValue();
            try {
                b.withShaderDefine(d.getKey(), Integer.parseInt(v));
            } catch (NumberFormatException e) {
                b.withShaderDefine(d.getKey(), Float.parseFloat(v));
            }
        }
        for (String flag : base.getShaderDefines().flags()) {
            b.withShaderDefine(flag);
        }
        for (BindGroupLayout layout : base.getBindGroupLayouts()) {
            b.withBindGroupLayout(layout);
        }
        if (extra != null) {
            b.withBindGroupLayout(extra);
        }
        b.withVertexBinding(0, vertex).withVertexBinding(1, instance)
            .withPrimitiveTopology(base.getPrimitiveTopology())
            .withCull(cull)
            .withPolygonMode(base.getPolygonMode())
            .withDepthStencilState(java.util.Optional.ofNullable(base.getDepthStencilState()));
        List<com.mojang.renderpearl.api.pipeline.ColorTargetState> targets = base.getColorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            b.withColorTargetState(i, targets.get(i));
        }
        return b.build();
    }

    private static String read(ResourceManager resources, Identifier id, ShaderType type) throws IOException {
        Identifier file = id.withPath(p -> "shaders/" + p + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
        try (InputStream in = resources.open(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The pack's vertex shader with our inputs: its #version / #extension lines stay first, then our declarations and
     * the globals its code reads (Position, Color, ...), then its code without those input declarations and with main()
     * renamed, then our main.
     */
    static String adapt(String src, String decl, String main) {
        String body = src.replace("\r\n", "\n");
        StringBuilder head = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int end = body.indexOf('\n', pos);
            if (end < 0) {
                end = body.length();
            }
            String line = body.substring(pos, end).trim();
            if (line.startsWith("#version") || line.startsWith("#extension") || line.isEmpty() || line.startsWith("//")) {
                head.append(body, pos, Math.min(end + 1, body.length()));
                pos = end + 1;
            } else {
                break;
            }
        }
        if (!head.toString().contains("#version")) {
            throw new IllegalArgumentException("the item shader has no #version line first");
        }
        String rest = body.substring(Math.min(pos, body.length()));
        Matcher m = INPUT_DECL.matcher(rest);
        rest = m.replaceAll("// (RemoteRenderer: input $1 is filled by its own main)");
        Matcher mm = MAIN.matcher(rest);
        if (!mm.find()) {
            throw new IllegalArgumentException("the item shader has no main()");
        }
        rest = mm.replaceFirst("void rr_pack_main()");
        return head + decl + "\n" + rest + "\n" + main;
    }

    private static final String GLOBALS = """
        vec3 Position;
        vec4 Color;
        vec2 UV0;
        ivec2 UV1;
        ivec2 UV2;
        vec2 UV3;
        vec3 Normal;
        """;

    private static final String LIGHT = """
        ivec2 rr_light(ivec2 uv2, float extra) {
            int emission = int(extra * 255.0 + 0.5);
            if (emission > 0) {
                uv2 = ivec2(max((uv2.x >> 4) & 15, emission) << 4, max((uv2.y >> 4) & 15, emission) << 4);
            }
            return uv2;
        }
        """;

    private static final String MESH_WRAPPER_DECL = """
        // ---- RemoteRenderer: this item shader runs instanced; its inputs are filled below from the baked model ----
        layout(location = 0) in vec3 RrPosition;
        layout(location = 1) in vec4 RrColor;
        layout(location = 2) in vec2 RrUV0;
        layout(location = 3) in vec3 RrNormal;
        layout(location = 4) in vec4 RrExtra;
        layout(location = 5) in mat4 RrModel;
        layout(location = 9) in mat3 RrNormalMat;
        layout(location = 12) in ivec4 RrLight;
        """ + GLOBALS + LIGHT;

    private static final String MESH_WRAPPER_MAIN = """
        void main() {
            Position = (RrModel * vec4(RrPosition, 1.0)).xyz;
            Normal = normalize(RrNormalMat * RrNormal);
            Color = RrColor;
            UV0 = RrUV0;
            UV1 = RrLight.zw;
            UV2 = rr_light(RrLight.xy, RrExtra.x);
            UV3 = vec2(0.0);
            rr_pack_main();
        }
        """;

    private static final String RIG_WRAPPER_DECL = """
        // ---- RemoteRenderer: this item shader runs on a skinned mesh; its inputs are filled below ----
        layout(location = 0) in vec3 RrPosition;
        layout(location = 1) in vec4 RrColor;
        layout(location = 2) in vec2 RrUV0;
        layout(location = 3) in vec3 RrNormal;
        layout(location = 4) in vec4 RrExtra;
        layout(location = 5) in uvec4 RrJoints;
        layout(location = 6) in vec4 RrWeights;
        layout(location = 7) in ivec4 RrRig;
        uniform samplerBuffer RigJoints;
        """ + GLOBALS + LIGHT;

    private static final String RIG_WRAPPER_MAIN = """
        void main() {
            vec4 p = vec4(RrPosition, 1.0);
            vec3 pos = vec3(0.0);
            vec3 nrm = vec3(0.0);
            float total = 0.0;
            for (int k = 0; k < 4; k++) {
                float w = RrWeights[k];
                if (w > 0.0) {
                    int t = (RrRig.x + int(RrJoints[k])) * 3;
                    vec4 r0 = texelFetch(RigJoints, t);
                    vec4 r1 = texelFetch(RigJoints, t + 1);
                    vec4 r2 = texelFetch(RigJoints, t + 2);
                    pos += w * vec3(dot(r0, p), dot(r1, p), dot(r2, p));
                    nrm += w * vec3(dot(r0.xyz, RrNormal), dot(r1.xyz, RrNormal), dot(r2.xyz, RrNormal));
                    total += w;
                }
            }
            Position = pos / max(total, 1e-6);
            Normal = length(nrm) > 1e-6 ? normalize(nrm) : vec3(0.0, 1.0, 0.0);
            int tint = RrRig.z;
            Color = RrColor * vec4(float((tint >> 16) & 255), float((tint >> 8) & 255), float(tint & 255), float((tint >> 24) & 255)) / 255.0;
            UV0 = RrUV0;
            UV1 = ivec2(0, 10);
            UV2 = rr_light(ivec2(RrRig.y & 0xFFFF, (RrRig.y >> 16) & 0xFFFF), RrExtra.x);
            UV3 = vec2(0.0);
            rr_pack_main();
        }
        """;

    /** Names the pack's code reads as inputs (for the log). */
    static List<String> inputs() {
        return new ArrayList<>(List.of(INPUTS));
    }
}
