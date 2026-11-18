package kr.gcb.remoterenderer.api;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.gcb.remoterenderer.rig.RigDef;
import kr.gcb.remoterenderer.rig.RigManager;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * For other client mods. Rigs normally come from resource packs (assets/<ns>/remoterenderer/rigs/*.json, format in
 * tools/rig/README.md); this registers them from code and lets code move joints on the client (look-at, IK,
 * physics, procedural animation) after the server-driven bones and the clips have been applied.
 */
public final class RemoteRendererApi {
    private RemoteRendererApi() {
    }

    /**
     * Called for every drawn instance of a rig, every frame, on the render thread.
     *
     * world[j] is joint j's matrix in camera-relative render space (rig space at rest -> where it is drawn now), in the
     * rig's joint order (names in rig.joints). Change them in place; the skin matrices are world[j] * inverseBind[j].
     * instance is what the bone displays ride (an Entity), seconds the client time since the instance appeared.
     */
    @FunctionalInterface
    public interface JointHook {
        void adjust(RigDef rig, Object instance, Matrix4f[] world, float seconds);
    }

    private static final List<JointHook> HOOKS = new CopyOnWriteArrayList<>();

    /** A rig from code, same JSON as in a resource pack; takes effect with the next resource reload (F3+T). */
    public static void registerRig(Identifier id, JsonObject json) {
        RigManager.register(RigDef.parse(id, json));
    }

    public static void unregisterRig(Identifier id) {
        RigManager.unregister(id);
    }

    public static List<Identifier> rigs() {
        List<Identifier> out = new ArrayList<>();
        for (RigManager.Rig r : RigManager.rigs()) {
            out.add(r.def.id);
        }
        return out;
    }

    /** How many instances of the rig were drawn in the last frame (0 if none or unknown). */
    public static int instances(Identifier rig) {
        for (RigManager.Rig r : RigManager.rigs()) {
            if (r.def.id.equals(rig)) {
                return r.lastInstances;
            }
        }
        return 0;
    }

    public static void addJointHook(JointHook hook) {
        HOOKS.add(hook);
    }

    public static void removeJointHook(JointHook hook) {
        HOOKS.remove(hook);
    }

    /** Internal. */
    public static List<JointHook> hooks() {
        return HOOKS;
    }
}
