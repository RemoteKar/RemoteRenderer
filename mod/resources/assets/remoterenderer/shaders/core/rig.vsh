#version 330
#extension GL_ARB_separate_shader_objects : require

// RemoteRenderer rigs: a skinned mesh. Each vertex is moved by up to four joints; a joint's skin matrix (rig space
// at rest -> camera-relative render space) is three RGBA32F texels of RigJoints, from the instance's first joint on.
// Lighting, light map and fog as in mesh.vsh (core/item.vsh for cutout item quads).

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

uniform sampler2D Sampler2;
uniform samplerBuffer RigJoints;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec3 Normal;
layout(location = 4) in vec4 Extra;      // x * 255 = light emission (0..15)
layout(location = 5) in uvec4 Joints;
layout(location = 6) in vec4 Weights;
layout(location = 7) in ivec4 IRig;      // x = first joint, y = light coords (block | sky << 16), z = tint ARGB

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec2 texCoord0;

void main() {
    vec4 p = vec4(Position, 1.0);
    vec3 pos = vec3(0.0);
    vec3 nrm = vec3(0.0);
    float total = 0.0;
    for (int k = 0; k < 4; k++) {
        float w = Weights[k];
        if (w > 0.0) {
            int t = (IRig.x + int(Joints[k])) * 3;
            vec4 r0 = texelFetch(RigJoints, t);
            vec4 r1 = texelFetch(RigJoints, t + 1);
            vec4 r2 = texelFetch(RigJoints, t + 2);
            pos += w * vec3(dot(r0, p), dot(r1, p), dot(r2, p));
            nrm += w * vec3(dot(r0.xyz, Normal), dot(r1.xyz, Normal), dot(r2.xyz, Normal));
            total += w;
        }
    }
    pos /= max(total, 1e-6);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = length(pos);
    cylindricalVertexDistance = max(length(pos.xz), abs(pos.y));

    vec3 normal = length(nrm) > 1e-6 ? normalize(nrm) : vec3(0.0, 1.0, 0.0);
    vec2 light = max(vec2(0.0), vec2(dot(Light0_Direction, normal), dot(Light1_Direction, normal)));
    float lightAccum = min(1.0, (light.x + light.y) * 0.6 + 0.4);
    int tint = IRig.z;
    vec4 tintColor = vec4(float((tint >> 16) & 255), float((tint >> 8) & 255), float(tint & 255), float((tint >> 24) & 255)) / 255.0;
    vertexColor = vec4(Color.rgb * tintColor.rgb * lightAccum, Color.a * tintColor.a);

    ivec2 uv2 = ivec2(IRig.y & 0xFFFF, (IRig.y >> 16) & 0xFFFF);
    int emission = int(Extra.x * 255.0 + 0.5);
    if (emission > 0) {
        uv2 = ivec2(max((uv2.x >> 4) & 15, emission) << 4, max((uv2.y >> 4) & 15, emission) << 4);
    }
    lightMapColor = texture(Sampler2, clamp((vec2(uv2) / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    texCoord0 = UV0;
}
