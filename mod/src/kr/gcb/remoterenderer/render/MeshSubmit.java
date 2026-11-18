package kr.gcb.remoterenderer.render;

import java.util.ArrayList;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * One item display to draw from a baked mesh, with the pose the vanilla renderer would have used.
 * Pooled: a frame with thousands of displays allocates nothing here (no garbage, no collector pauses).
 * Render thread only; the pool is handed back when the feature frame has been drawn.
 */
public final class MeshSubmit implements SubmitNode {
    private static final ArrayList<MeshSubmit> POOL = new ArrayList<>();
    private static int used;

    public MeshEntry entry;
    /** pose (16, column major) followed by the normal matrix (9, column major) */
    public final float[] matrix = new float[25];
    public int light;
    public int overlay;
    public int[] objmcTints;

    private MeshSubmit() {
    }

    public static MeshSubmit obtain(MeshEntry entry, Matrix4fc pose, Matrix3fc normal, int light, int overlay, int[] objmcTints) {
        MeshSubmit s;
        if (used < POOL.size()) {
            s = POOL.get(used);
        } else {
            s = new MeshSubmit();
            POOL.add(s);
        }
        used++;
        s.entry = entry;
        pose.get(s.matrix, 0);
        normal.get(s.matrix, 16);
        s.light = light;
        s.overlay = overlay;
        s.objmcTints = objmcTints;
        return s;
    }

    static void releaseAll() {
        for (int i = 0; i < used; i++) {
            MeshSubmit s = POOL.get(i);
            s.entry = null;
            s.objmcTints = null;
        }
        used = 0;
    }

    @Override
    public FeatureRendererType<MeshSubmit> featureType() {
        return MeshFeatureRenderer.TYPE;
    }
}
