package kr.gcb.remoterenderer.rig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A rig: which item displays are the bones of one model, and what to draw instead of them.
 *
 * Bones are found by the item_model of a display's item. All bones of one model ride the same vehicle (BetterModel
 * and most plugins put the bone displays on one base entity), so the displays riding one entity form one instance.
 * Each frame the pose of every bone display becomes a joint matrix, and one skinned mesh is drawn on the GPU.
 *
 * Two kinds of geometry:
 *  - "bones": the bones' own item models, with the vertices near the joints listed in "blend" weighted between the
 *    two bones (a knee bends smoothly instead of two rigid pieces crossing each other). Needs nothing but this file.
 *  - a mesh file: any mesh with its own skin weights, skeleton, textures and animation clips (from glTF, by the tool).
 *
 * Space: "rig space" is the space the bone displays' transformations map into (relative to the vehicle). A bone's
 * "rest" is the transformation its display has in the rest pose, so a vertex of the rest mesh goes, for bone b,
 * through pose_b * rest_b^-1. See tools/rig/README.md for the file format.
 */
public final class RigDef {
    public static final int MAX_JOINTS = 256;

    public final Identifier id;
    public final List<Bone> bones;
    public final Map<String, Integer> boneIndex = new HashMap<>();
    public final int anchor;
    public final boolean hideBones;
    /** null: the bones' own models ("bones" geometry) */
    public final @Nullable MeshSpec mesh;
    public final List<Blend> blends;
    public final Matrix4f skeletonRoot;
    public final List<Joint> joints;
    public final Map<String, Clip> clips;
    public final @Nullable String autoplay;
    public final float cutout;
    /** Capsules the spring joints keep out of (legs under a skirt). */
    public final List<Collider> colliders;
    /** Spring tails that keep their animated distance (neighbouring chains of a skirt). */
    public final List<Link> links;
    /** false: "physics": false in the file - the springs are declared but off. */
    public final boolean physics;
    /** any joint has a spring */
    public final boolean hasSprings;

    /**
     * A bone display: its item_model and, when the plugin tells its bones apart by custom_model_data instead (BetterModel
     * 3.3 puts every bone on leather_horse_armor with its own number), that number (NaN: not used).
     */
    public record Bone(String name, Identifier itemModel, float customModelData, Matrix4f rest, Matrix4f restInverse) {
    }

    /**
     * A joint between two bones of "bones" geometry: vertices within length of "at" along axis are shared. side: which
     * bones' vertices are blended (BOTH, CHILD only, PARENT only); half: overlapping rounded parts (see RigGeometry).
     */
    public record Blend(int parent, int child, Vector3f at, Vector3f axis, float length, float radius, int side, boolean half) {
        public static final int BOTH = 0, CHILD = 1, PARENT = 2;
    }

    /**
     * A skeleton joint. world = (driving bone's pose * rest^-1) * restWorld if bone >= 0 and that bone is drawn,
     * else parent world * local (local from the playing clip, or the rest local). skin matrix = world * inverseBind.
     */
    public static final class Joint {
        public final String name;
        public final int parent;
        public final Matrix4f local;
        public final Matrix4f inverseBind;
        public final int bone;
        public final @Nullable Spring spring;
        /** rest world in rig space (computed) */
        public final Matrix4f restWorld = new Matrix4f();

        public Joint(String name, int parent, Matrix4f local, Matrix4f inverseBind, int bone, @Nullable Spring spring) {
            this.name = name;
            this.parent = parent;
            this.local = local;
            this.inverseBind = inverseBind;
            this.bone = bone;
            this.spring = spring;
        }
    }

    /**
     * Client-side secondary motion of a joint (hair, ribbons, skirts; see RigPhysics). Its tail - the child joint, or the
     * given point in the joint's rest space - trails behind where the animation puts it and swings back.
     * stiffness: share of the way back to the animated position per 1/60 s (0..1); drag: share of the velocity lost per
     * 1/60 s; gravity: blocks/s^2 downwards; radius: the tail's size against colliders (rig units); maxAngle: degrees
     * the joint may turn away from the animated pose (0 = no limit).
     */
    public record Spring(float stiffness, float drag, float gravity, float radius, @Nullable Vector3f tail, float maxAngle) {
    }

    /** Two spring joints whose tails keep the distance the animation has between them; stiffness 0..1 per round. */
    public record Link(int a, int b, float stiffness) {
    }

    /** A capsule (from == to: a sphere) in a joint's rest space (rig units). */
    public record Collider(int joint, Vector3f from, Vector3f to, float radius) {
    }

    public record Submesh(@Nullable Identifier texture, int firstIndex, int indexCount, boolean doubleSided, boolean linear) {
    }

    /** Geometry file: vertexCount x 40 bytes at vertexOffset, indexCount uint32 at indexOffset, clip data at its offsets. */
    public record MeshSpec(Identifier data, int vertexCount, int indexCount, long vertexOffset, long indexOffset, List<Submesh> submeshes) {
    }

    /** Sampled local transforms: frames x joints.length x (tx ty tz qx qy qz qw sx sy sz). */
    public static final class Clip {
        public final String name;
        public final float fps;
        public final int frames;
        public final boolean loop;
        public final int[] joints;
        public final long offset;
        /** filled when the rig's data file is loaded */
        public float @Nullable [] data;

        Clip(String name, float fps, int frames, boolean loop, int[] joints, long offset) {
            this.name = name;
            this.fps = fps;
            this.frames = frames;
            this.loop = loop;
            this.joints = joints;
            this.offset = offset;
        }

        public float duration() {
            return this.frames <= 1 ? 0.0F : (this.frames - 1) / this.fps;
        }
    }

    private RigDef(Identifier id, List<Bone> bones, int anchor, boolean hideBones, @Nullable MeshSpec mesh, List<Blend> blends, Matrix4f skeletonRoot,
                   List<Joint> joints, Map<String, Clip> clips, @Nullable String autoplay, float cutout, List<Collider> colliders, List<Link> links,
                   boolean physics) {
        this.id = id;
        this.bones = bones;
        for (int i = 0; i < bones.size(); i++) {
            this.boneIndex.put(bones.get(i).name(), i);
        }
        this.anchor = anchor;
        this.hideBones = hideBones;
        this.mesh = mesh;
        this.blends = blends;
        this.skeletonRoot = skeletonRoot;
        this.joints = joints;
        this.clips = clips;
        this.autoplay = autoplay;
        this.cutout = cutout;
        this.colliders = colliders;
        this.links = links;
        this.physics = physics;
        this.hasSprings = joints.stream().anyMatch(j -> j.spring != null);
        for (Joint j : joints) {
            if (j.parent >= 0) {
                joints.get(j.parent).restWorld.mul(j.local, j.restWorld);
            } else {
                skeletonRoot.mul(j.local, j.restWorld);
            }
        }
    }

    public boolean boneGeometry() {
        return this.mesh == null;
    }

    // ---------------------------------------------------------------- parsing

    public static RigDef parse(Identifier id, JsonObject json) {
        int format = json.has("format") ? json.get("format").getAsInt() : 1;
        if (format != 1) {
            throw new IllegalArgumentException("unknown rig format " + format);
        }
        List<Bone> bones = new ArrayList<>();
        List<JsonObject> boneJson = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        for (JsonElement e : json.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            String name = b.get("name").getAsString();
            Identifier model = Identifier.parse(b.get("item_model").getAsString());
            Matrix4f rest;
            if (b.has("rest")) {
                rest = matrix(b.getAsJsonArray("rest"));
            } else if (b.has("pivot")) {
                Vector3f p = vec(b.getAsJsonArray("pivot"));
                rest = new Matrix4f().translation(p);
            } else {
                rest = new Matrix4f();
            }
            if (names.put(name, bones.size()) != null) {
                throw new IllegalArgumentException("bone " + name + " twice");
            }
            float cmd = b.has("custom_model_data") ? b.get("custom_model_data").getAsFloat() : Float.NaN;
            bones.add(new Bone(name, model, cmd, rest, new Matrix4f(rest).invert()));
            boneJson.add(b);
        }
        if (bones.isEmpty()) {
            throw new IllegalArgumentException("no bones");
        }
        int anchor = json.has("anchor") ? bone(names, json.get("anchor").getAsString()) : 0;
        boolean hide = !json.has("hide_bones") || json.get("hide_bones").getAsBoolean();
        float cutout = json.has("cutout") ? json.get("cutout").getAsFloat() : 0.1F;

        MeshSpec mesh = null;
        List<Blend> blends = new ArrayList<>();
        Matrix4f root = new Matrix4f();
        List<Joint> joints = new ArrayList<>();
        JsonElement geometry = json.get("geometry");
        if (geometry == null || geometry.isJsonPrimitive() && "bones".equals(geometry.getAsString())) {
            // one joint per bone, driven by it; the vertices stay where the bone models put them. A bone's "parent" only
            // matters for physics: a spring on a bone also carries the bones below it.
            for (int i = 0; i < bones.size(); i++) {
                Bone b = bones.get(i);
                JsonObject bj = boneJson.get(i);
                int parent = bj.has("parent") ? bone(names, bj.get("parent").getAsString()) : -1;
                if (parent >= i) {
                    throw new IllegalArgumentException("bone " + b.name() + ": its parent must come first");
                }
                Matrix4f local = parent < 0 ? new Matrix4f(b.rest()) : new Matrix4f(bones.get(parent).restInverse()).mul(b.rest());
                joints.add(new Joint(b.name(), parent, local, new Matrix4f(b.restInverse()), i, spring(bj)));
            }
            if (json.has("blend")) {
                for (JsonElement e : json.getAsJsonArray("blend")) {
                    JsonObject o = e.getAsJsonObject();
                    int parent = bone(names, o.get("parent").getAsString());
                    int child = bone(names, o.get("child").getAsString());
                    Vector3f at = o.has("at") ? vec(o.getAsJsonArray("at")) : bones.get(child).rest().getTranslation(new Vector3f());
                    Vector3f axis = o.has("axis") ? vec(o.getAsJsonArray("axis")).normalize() : new Vector3f(0, -1, 0);
                    float length = o.has("length") ? o.get("length").getAsFloat() : 0.05F;
                    float radius = o.has("radius") ? o.get("radius").getAsFloat() : 0.1F;
                    String side = o.has("side") ? o.get("side").getAsString() : "both";
                    int s = switch (side) {
                        case "both" -> Blend.BOTH;
                        case "child" -> Blend.CHILD;
                        case "parent" -> Blend.PARENT;
                        default -> throw new IllegalArgumentException("blend side " + side + " (both, child, parent)");
                    };
                    String falloff = o.has("falloff") ? o.get("falloff").getAsString() : "smooth";
                    if (!falloff.equals("smooth") && !falloff.equals("half")) {
                        throw new IllegalArgumentException("blend falloff " + falloff + " (smooth, half)");
                    }
                    blends.add(new Blend(parent, child, at, axis, length, radius, s, falloff.equals("half")));
                }
            }
        } else {
            JsonObject g = geometry.getAsJsonObject();
            List<Submesh> subs = new ArrayList<>();
            int vertices = g.get("vertices").getAsInt();
            int indices = g.get("indices").getAsInt();
            for (JsonElement e : g.getAsJsonArray("submeshes")) {
                JsonObject s = e.getAsJsonObject();
                Identifier tex = s.has("texture") ? Identifier.parse(s.get("texture").getAsString()) : null;
                subs.add(new Submesh(tex, s.get("first").getAsInt(), s.get("count").getAsInt(),
                    s.has("double_sided") && s.get("double_sided").getAsBoolean(), !s.has("filter") || !"nearest".equals(s.get("filter").getAsString())));
            }
            mesh = new MeshSpec(Identifier.parse(g.get("data").getAsString()), vertices, indices,
                g.has("vertex_offset") ? g.get("vertex_offset").getAsLong() : 0L,
                g.has("index_offset") ? g.get("index_offset").getAsLong() : (long) vertices * RigGeometry.VERTEX_SIZE, subs);
            JsonObject skeleton = json.getAsJsonObject("skeleton");
            if (skeleton.has("root")) {
                root = matrix(skeleton.getAsJsonArray("root"));
            }
            for (JsonElement e : skeleton.getAsJsonArray("joints")) {
                JsonObject j = e.getAsJsonObject();
                int parent = j.has("parent") ? j.get("parent").getAsInt() : -1;
                if (parent >= joints.size()) {
                    throw new IllegalArgumentException("joint " + joints.size() + ": parents must come first");
                }
                Spring spring = spring(j);
                joints.add(new Joint(j.get("name").getAsString(), parent, j.has("local") ? matrix(j.getAsJsonArray("local")) : new Matrix4f(),
                    j.has("inverse_bind") ? matrix(j.getAsJsonArray("inverse_bind")) : new Matrix4f(),
                    j.has("bone") ? bone(names, j.get("bone").getAsString()) : -1, spring));
            }
        }
        if (joints.size() > MAX_JOINTS) {
            throw new IllegalArgumentException(joints.size() + " joints (at most " + MAX_JOINTS + ")");
        }

        Map<String, Clip> clips = new LinkedHashMap<>();
        if (json.has("clips")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("clips").entrySet()) {
                JsonObject c = e.getValue().getAsJsonObject();
                JsonArray js = c.getAsJsonArray("joints");
                int[] cj = new int[js.size()];
                for (int i = 0; i < cj.length; i++) {
                    cj[i] = js.get(i).getAsInt();
                    if (cj[i] < 0 || cj[i] >= joints.size()) {
                        throw new IllegalArgumentException("clip " + e.getKey() + ": joint " + cj[i]);
                    }
                }
                clips.put(e.getKey(), new Clip(e.getKey(), c.get("fps").getAsFloat(), c.get("frames").getAsInt(),
                    !c.has("loop") || c.get("loop").getAsBoolean(), cj, c.get("offset").getAsLong()));
            }
        }
        String autoplay = json.has("autoplay") ? json.get("autoplay").getAsString() : null;
        Map<String, Integer> jointNames = new HashMap<>();
        for (int i = 0; i < joints.size(); i++) {
            jointNames.putIfAbsent(joints.get(i).name, i);
        }
        List<Collider> colliders = new ArrayList<>();
        if (json.has("colliders")) {
            for (JsonElement e : json.getAsJsonArray("colliders")) {
                JsonObject c = e.getAsJsonObject();
                String jn = c.has("joint") ? c.get("joint").getAsString() : c.get("bone").getAsString();
                Integer ji = jointNames.get(jn);
                if (ji == null) {
                    throw new IllegalArgumentException("collider: no joint " + jn);
                }
                Vector3f from = c.has("from") ? vec(c.getAsJsonArray("from")) : new Vector3f();
                Vector3f to = c.has("to") ? vec(c.getAsJsonArray("to")) : new Vector3f(from);
                colliders.add(new Collider(ji, from, to, f(c, "radius", 1.0F)));
            }
        }
        List<Link> links = new ArrayList<>();
        if (json.has("links")) {
            for (JsonElement e : json.getAsJsonArray("links")) {
                JsonObject l = e.getAsJsonObject();
                Integer a = jointNames.get(l.get("a").getAsString()), b = jointNames.get(l.get("b").getAsString());
                if (a == null || b == null) {
                    throw new IllegalArgumentException("link: no joint " + (a == null ? l.get("a") : l.get("b")));
                }
                if (joints.get(a).spring == null || joints.get(b).spring == null) {
                    throw new IllegalArgumentException("link " + l.get("a") + " - " + l.get("b") + ": both joints need a spring");
                }
                links.add(new Link(a, b, Math.clamp(f(l, "stiffness", 0.6F), 0.0F, 1.0F)));
            }
        }
        boolean physics = !json.has("physics") || json.get("physics").getAsBoolean();
        return new RigDef(id, bones, anchor, hide, mesh, blends, root, joints, clips, autoplay, cutout, colliders, links, physics);
    }

    private static @Nullable Spring spring(JsonObject o) {
        if (!o.has("spring")) {
            return null;
        }
        JsonObject s = o.getAsJsonObject("spring");
        return new Spring(f(s, "stiffness", 0.08F), f(s, "drag", 0.12F), f(s, "gravity", 6.0F), f(s, "radius", 0.0F),
            s.has("tail") ? vec(s.getAsJsonArray("tail")) : null, f(s, "max_angle", 0.0F));
    }

    private static float f(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    private static int bone(Map<String, Integer> names, String name) {
        Integer i = names.get(name);
        if (i == null) {
            throw new IllegalArgumentException("no bone " + name);
        }
        return i;
    }

    private static Matrix4f matrix(JsonArray a) {
        if (a.size() != 16) {
            throw new IllegalArgumentException("a matrix needs 16 numbers (column major)");
        }
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) {
            m[i] = a.get(i).getAsFloat();
        }
        return new Matrix4f().set(m);
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}
