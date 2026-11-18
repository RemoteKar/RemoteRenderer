#version 330
#extension GL_ARB_separate_shader_objects : require

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

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 overlayColor;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec2 texCoord0;
layout(location = 5) in vec3 viewPos;
layout(location = 6) flat in int noShadow;

layout(location = 0) out vec4 fragColor;

float linearFog(float d, float start, float end) {
    if (d <= start) return 0.0;
    if (d >= end) return 1.0;
    return (d - start) / (end - start);
}

void main() {
    // derivatives first, outside any branch
    vec3 normal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    // objmc's flat face normal; turned towards the camera so OpenGL and Vulkan (flipped y) agree
    if (dot(normal, viewPos) > 0.0) {
        normal = -normal;
    }

    vec4 color = texture(Sampler0, texCoord0);
    if (noShadow == 0) {
        vec2 light = max(vec2(0.0), vec2(dot(Light0_Direction, normal), dot(Light1_Direction, normal)));
        float lightAccum = min(1.0, (light.x + light.y) * 0.6 + 0.4);
        color *= vec4(overlayColor.rgb * lightAccum, overlayColor.a);
    }
    color *= lightMapColor;
    if (color.a < 0.1) {
        discard;
    }
    float fog = max(linearFog(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd),
                    linearFog(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd));
    fragColor = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}
