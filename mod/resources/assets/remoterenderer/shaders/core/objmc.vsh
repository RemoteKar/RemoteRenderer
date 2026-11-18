#version 330
#extension GL_ARB_separate_shader_objects : require

// RemoteRenderer: an objmc model (https://github.com/Godlander/objmc) drawn from data decoded once on the CPU.
// ObjmcData holds, for every (frame, vertex): texel 2i = position (blocks, model space), texel 2i+1 = atlas uv.
// The animation (frame choice, easing) is the objmc shader's, evaluated here per vertex on the GPU.
// IModel places the model: translation = the dummy quad's corner 2, linear part = objmc's rotation
// (from the item colour, or "autorotate" from the quad's own edges) times the colour scale.

layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler2;
uniform sampler2D ObjmcData;

layout(location = 0) in int VertexId;
layout(location = 1) in mat4 IModel;     // 1..4
layout(location = 5) in vec4 IObj;       // time colour, autoplay, duration (ticks), frame count
layout(location = 6) in ivec4 IObj2;     // vertex count, easing, noshadow, unused
layout(location = 7) in vec4 IOverlay;   // objmc overlay colour (hue / hurt tint), white otherwise
layout(location = 8) in ivec4 ILight;    // xy = UV2

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 overlayColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec2 texCoord0;
layout(location = 5) out vec3 viewPos;
layout(location = 6) flat out int noShadow;

vec4 fetchData(int texel) {
    return texelFetch(ObjmcData, ivec2(texel % DATA_WIDTH, texel / DATA_WIDTH), 0);
}

// 4 point bezier formula from Dominexis (as in objmc_tools.glsl)
vec3 bezb(vec3 a, vec3 b, vec3 c, vec3 d, float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return (d - 3.0 * c + 3.0 * b - a) * t3 + (3.0 * c - 6.0 * b + 3.0 * a) * t2 + (3.0 * b - 3.0 * a) * t + a;
}

vec3 bezier(vec3 a, vec3 b, vec3 c, vec3 d, float t) {
    return bezb(b, b + (c - a) / 6.0, c - (d - b) / 6.0, c, t);
}

void main() {
    int nvertices = IObj2.x;
    int easing = IObj2.y;
    float tcolor = IObj.x;
    bool autoplay = IObj.y > 0.5;
    float duration = max(IObj.z, 1.0);
    int nframes = max(int(IObj.w + 0.5), 1);

    float time = GameTime * 24000.0;
    time = autoplay ? time + duration - mod(tcolor, duration) : tcolor;
    float framePos = time * float(nframes) / duration;
    int frame = int(framePos) % nframes;
    int id = VertexId + frame * nvertices;

    vec3 p = fetchData(2 * id).xyz;
    vec2 uv = fetchData(2 * id + 1).xy;
    if (nframes > 1) {
        int nids = nframes * nvertices;
        int id2 = (id + nvertices) % nids;
        vec3 p2 = fetchData(2 * id2).xyz;
        float t = fract(framePos);
        if (easing == 1) {
            p = mix(p, p2, t);
        } else if (easing == 2) {
            t = t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) * 0.5;
            p = mix(p, p2, t);
        } else if (easing == 3) {
            int id3 = (id2 + nvertices) % nids;
            int id4 = (id3 + nvertices) % nids;
            p = bezier(p, p2, fetchData(2 * id3).xyz, fetchData(2 * id4).xyz, t);
        }
    }

    vec4 pos = IModel * vec4(p, 1.0);
    viewPos = pos.xyz;
    gl_Position = ProjMat * ModelViewMat * pos;
    sphericalVertexDistance = length(pos.xyz);
    cylindricalVertexDistance = max(length(pos.xz), abs(pos.y));

    lightMapColor = texture(Sampler2, clamp((vec2(ILight.xy) / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = IOverlay;
    noShadow = IObj2.z;
    texCoord0 = uv;
}
