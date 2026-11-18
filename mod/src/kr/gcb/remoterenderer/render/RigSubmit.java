package kr.gcb.remoterenderer.render;

import java.util.ArrayList;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.rig.RigManager;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** One bone display of a rig this frame: its pose (camera relative, before the item's own transforms). Pooled. */
public final class RigSubmit implements SubmitNode {
    private static final ArrayList<RigSubmit> POOL = new ArrayList<>();
    private static int used;

    public RigManager.Rig rig;
    public int bone;
    /** what the bone displays of one model share: their vehicle */
    public Object instance;
    public final Matrix4f pose = new Matrix4f();
    public int light;
    public int tint;
    public @Nullable MeshEntry entry;
    public @Nullable String clip;
    public boolean physicsOff;

    private RigSubmit() {
    }

    public static RigSubmit obtain(RigManager.BoneRef ref, Object instance, Matrix4fc pose, int light, int tint, @Nullable MeshEntry entry,
                                   @Nullable String clip, boolean physicsOff) {
        RigSubmit s;
        if (used < POOL.size()) {
            s = POOL.get(used);
        } else {
            s = new RigSubmit();
            POOL.add(s);
        }
        used++;
        s.rig = ref.rig();
        s.bone = ref.bone();
        s.instance = instance;
        s.pose.set(pose);
        s.light = light;
        s.tint = tint;
        s.entry = entry;
        s.clip = clip;
        s.physicsOff = physicsOff;
        return s;
    }

    static void releaseAll() {
        for (int i = 0; i < used; i++) {
            RigSubmit s = POOL.get(i);
            s.rig = null;
            s.instance = null;
            s.entry = null;
            s.clip = null;
        }
        used = 0;
    }

    @Override
    public FeatureRendererType<RigSubmit> featureType() {
        return RigRenderer.TYPE;
    }
}
