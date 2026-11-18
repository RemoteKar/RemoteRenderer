package kr.gcb.remoterenderer.duck;

import kr.gcb.remoterenderer.mesh.EntityCache;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import kr.gcb.remoterenderer.rig.RigManager;
import org.jspecify.annotations.Nullable;

/** Added to ItemDisplayEntityRenderState: what this frame draws through RemoteRenderer. */
public interface RrRenderState {
    @Nullable MeshEntry remoterenderer$entry();

    /** The entity's cache (for the objmc tints and a late vanilla fallback). */
    @Nullable EntityCache remoterenderer$cache();

    void remoterenderer$set(@Nullable MeshEntry entry, @Nullable EntityCache cache);

    /** This display is a bone of a rig (null: it is not). */
    RigManager.@Nullable BoneRef remoterenderer$rig();

    /** What the bones of one rig instance share (the vehicle). */
    @Nullable Object remoterenderer$rigInstance();

    int remoterenderer$rigTint();

    @Nullable String remoterenderer$rigClip();

    boolean remoterenderer$rigPhysicsOff();

    void remoterenderer$setRig(RigManager.@Nullable BoneRef bone, @Nullable Object instance, int tint, @Nullable String clip, boolean physicsOff);
}
