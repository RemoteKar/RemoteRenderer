package kr.gcb.remoterenderer;

import kr.gcb.remoterenderer.mesh.MeshCache;
import net.minecraft.client.Minecraft;

/** Counters for /remoterenderer and the optional RR_STATS log line. Render thread only. */
public final class Stats {
    // filled while a world frame is prepared / drawn
    public long frameInstances, frameQuads, frameDraws;
    // the last finished world frame
    public long lastInstances, lastQuads, lastDraws;
    // since start
    public long baked, evicted, unsupported, cachedResolves, resolves, lateFallbacks, bakeNanos, maxBakeNanos, staticHits, bakesStarted, hiddenWhileBaking;
    // this frame: time spent resolving items the vanilla way / keying them (render thread)
    public long frameResolveNanos, frameKeyNanos, frameUploadNanos;
    // rigs: instances / joints / skinned vertices drawn in the last world frame
    public long rigBones;
    // off-screen culling: ordinary displays / rig instances not drawn this frame and in the last world frame
    public long frameMeshCulled, frameRigCulled, lastMeshCulled, lastRigCulled;
    public long frameFastEntities, frameEarlyCulled, lastFastEntities, lastEarlyCulled;
    public long frameRigInstances, frameRigJoints, frameRigVertices, lastRigInstances, lastRigJoints, lastRigVertices;
    private long frameResolvesAtStart, frameBakesAtStart;
    private static final int SLOW_MS = Integer.getInteger("remoterenderer.slowFrameMs", 0);

    private long frames;
    private long windowStart = System.nanoTime();
    private long windowFrames;
    private double windowFrameMsSum, windowFrameMsMax;
    private long lastFrameNanos;
    private long lastLog = System.nanoTime();
    public double avgFrameMs, maxFrameMs;

    /** Called at the start of every rendered frame (GameRenderer.render). */
    public void onFrame() {
        long now = System.nanoTime();
        if (this.lastFrameNanos != 0) {
            double ms = (now - this.lastFrameNanos) / 1.0e6;
            if (SLOW_MS > 0 && ms > SLOW_MS) {
                RemoteRenderer.LOG.info(String.format(java.util.Locale.ROOT, "RR_SLOW t=%d frame=%.1fms resolve=%.1fms key=%.1fms upload=%.1fms resolves=%d bakesStarted=%d pending=%d",
                    System.currentTimeMillis(), ms, this.frameResolveNanos / 1.0e6, this.frameKeyNanos / 1.0e6, this.frameUploadNanos / 1.0e6, this.resolves - this.frameResolvesAtStart,
                    this.bakesStarted - this.frameBakesAtStart, MeshCache.pending()));
            }
            this.windowFrameMsSum += ms;
            this.windowFrameMsMax = Math.max(this.windowFrameMsMax, ms);
            this.windowFrames++;
        }
        this.lastFrameNanos = now;
        this.frames++;
        // the frame counters roll over here, so a frame that draws nothing shows 0 (the renderers only fill them)
        this.endWorldFrame();
        this.endRigFrame();
        this.endCullFrame();
        this.frameResolveNanos = 0;
        this.frameKeyNanos = 0;
        this.frameUploadNanos = 0;
        this.frameResolvesAtStart = this.resolves;
        this.frameBakesAtStart = this.bakesStarted;
        if (now - this.windowStart >= 1_000_000_000L && this.windowFrames > 0) {
            this.avgFrameMs = this.windowFrameMsSum / this.windowFrames;
            this.maxFrameMs = this.windowFrameMsMax;
            this.windowStart = now;
            this.windowFrames = 0;
            this.windowFrameMsSum = 0.0;
            this.windowFrameMsMax = 0.0;
        }
        int every = RemoteRenderer.config.statsLogSeconds;
        if (every > 0 && now - this.lastLog >= every * 1_000_000_000L) {
            this.lastLog = now;
            RemoteRenderer.LOG.info("RR_STATS {}", this.line());
        }
    }

    public void endWorldFrame() {
        this.lastInstances = this.frameInstances;
        this.lastQuads = this.frameQuads;
        this.lastDraws = this.frameDraws;
        this.frameInstances = 0;
        this.frameQuads = 0;
        this.frameDraws = 0;
    }

    public void endRigFrame() {
        this.lastRigInstances = this.frameRigInstances;
        this.lastRigJoints = this.frameRigJoints;
        this.lastRigVertices = this.frameRigVertices;
        this.frameRigInstances = 0;
        this.frameRigJoints = 0;
        this.frameRigVertices = 0;
    }

    public void endCullFrame() {
        this.lastMeshCulled = this.frameMeshCulled;
        this.lastRigCulled = this.frameRigCulled;
        this.frameMeshCulled = 0;
        this.frameRigCulled = 0;
        this.lastFastEntities = this.frameFastEntities;
        this.lastEarlyCulled = this.frameEarlyCulled;
        this.frameFastEntities = 0;
        this.frameEarlyCulled = 0;
    }

    public String line() {
        Minecraft mc = Minecraft.getInstance();
        return String.format(java.util.Locale.ROOT,
            "active=%s fps=%d frameMs=%.2f maxMs=%.2f instances=%d quads=%d draws=%d models=%d vram=%.1fMB baked=%d bakeMs=%.1f maxBakeMs=%.1f pending=%d evicted=%d resolves=%d cachedResolves=%d staticHits=%d unsupported=%d lateFallbacks=%d rigs=%d rigInstances=%d rigJoints=%d rigVertices=%d rigTexMB=%.1f culled=%d rigCulled=%d fast=%d earlyCulled=%d",
            RemoteRenderer.active(), mc.getFps(), this.avgFrameMs, this.maxFrameMs, this.lastInstances, this.lastQuads, this.lastDraws, MeshCache.size(),
            MeshCache.residentBytes() / 1048576.0, this.baked, this.bakeNanos / 1.0e6, this.maxBakeNanos / 1.0e6, MeshCache.pending(), this.evicted,
            this.resolves, this.cachedResolves, this.staticHits, this.unsupported, this.lateFallbacks,
            kr.gcb.remoterenderer.rig.RigManager.rigs().size(), this.lastRigInstances, this.lastRigJoints, this.lastRigVertices,
            kr.gcb.remoterenderer.rig.RigManager.textureBytes() / 1048576.0, this.lastMeshCulled, this.lastRigCulled,
            this.lastFastEntities, this.lastEarlyCulled);
    }
}
