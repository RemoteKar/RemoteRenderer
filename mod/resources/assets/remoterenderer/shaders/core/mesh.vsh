#version 330
#extension GL_ARB_separate_shader_objects : require

// RemoteRenderer: an item-display model baked once into a static vertex buffer (model space of the item),
// drawn instanced. Per instance: the pose the vanilla renderer would have used, its normal matrix and the
// light / overlay coordinates. The result matches core/item.vsh + item.fsh for cutout item quads.
// Self-contained on purpose: a server resource pack that replaces minecraft:*.glsl includes cannot break it.

layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

// per vertex (static, model space)
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec3 Normal;
layout(location = 4) in vec4 Extra;      // x * 255 = light emission of the quad (0..15)
// per instance
layout(location = 5) in mat4 IModel;     // 5..8
layout(location = 9) in mat3 INormal;    // 9..11
layout(location = 12) in ivec4 ILight;   // xy = UV2 (block*16, sky*16), zw = UV1 (overlay)

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
layout(location = 5) out vec2 texCoord0;

void main() {
    vec4 pos = IModel * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * pos;

    sphericalVertexDistance = length(pos.xyz);
    cylindricalVertexDistance = max(length(pos.xz), abs(pos.y));

    vec3 normal = normalize(INormal * Normal);
    vec2 light = max(vec2(0.0), vec2(dot(Light0_Direction, normal), dot(Light1_Direction, normal)));
    float lightAccum = min(1.0, (light.x + light.y) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * lightAccum, Color.a);

    // LightCoordsUtil.lightCoordsWithEmission
    ivec2 uv2 = ILight.xy;
    int emission = int(Extra.x * 255.0 + 0.5);
    if (emission > 0) {
        uv2 = ivec2(max((uv2.x >> 4) & 15, emission) << 4, max((uv2.y >> 4) & 15, emission) << 4);
    }
    lightMapColor = texture(Sampler2, clamp((vec2(uv2) / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = texelFetch(Sampler1, ILight.zw, 0);

    texCoord0 = UV0;
}
