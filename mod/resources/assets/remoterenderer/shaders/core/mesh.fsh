#version 330
#extension GL_ARB_separate_shader_objects : require

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

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
layout(location = 5) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

float linearFog(float d, float start, float end) {
    if (d <= start) return 0.0;
    if (d >= end) return 1.0;
    return (d - start) / (end - start);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    float fog = max(linearFog(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd),
                    linearFog(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd));
    fragColor = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}
