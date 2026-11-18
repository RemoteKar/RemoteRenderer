package kr.gcb.remoterenderer.rig;

import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Spring joints: hair, ribbons, skirts. Client side only - every player sees their own sway, nothing is sent.
 *
 * Each spring joint has a tail (its child joint, or the farthest vertex it moves, or a given point); every tail is a
 * particle in world space, so it reacts to the model walking, turning and being knocked around. Each 1/60 s step:
 *  1. every particle keeps (1 - drag) of its velocity, moves stiffness of the way to where the animation puts it (seen
 *     from its head, which is the parent spring's particle in a chain) and falls by gravity;
 *  2. constraints, a few rounds: each tail stays at its joint's length from its head; linked particles (neighbouring
 *     chains of a skirt) keep the distance the animation has between them, so the skirt does not split open; tails
 *     are pushed out of the colliders (unless the joint starts inside one).
 * Then every spring joint is turned so it points at its particle, and the joints below it follow. Rendering
 * interpolates between the last two steps, so it is smooth at any frame rate.
 */
public final class RigPhysics {
    private static final float STEP = 1.0F / 60.0F;
    private static final int ROUNDS = 4;
    private static final String DEBUG = System.getProperty("remoterenderer.debugSpring");
    private static int debugCount;

    /** Per instance. */
    public static final class State {
        final double[] cur, prev, next;
        final boolean[] live;
        final double[] lastHead;
        long lastNanos;
        float accum;
        final Matrix4f[] corr;
        final boolean[] corrected;
        final Matrix4f[] snapshot;
        /** per joint: animated head / tail (world), joint length */
        final double[] animHead, animTail;
        final float[] length;

        public State(int joints) {
            this.cur = new double[joints * 3];
            this.prev = new double[joints * 3];
            this.next = new double[joints * 3];
            this.live = new boolean[joints];
            this.lastHead = new double[joints * 3];
            this.animHead = new double[joints * 3];
            this.animTail = new double[joints * 3];
            this.length = new float[joints];
            this.corr = new Matrix4f[joints];
            this.snapshot = new Matrix4f[joints];
            for (int i = 0; i < joints; i++) {
                this.corr[i] = new Matrix4f();
                this.snapshot[i] = new Matrix4f();
            }
            this.corrected = new boolean[joints];
        }

        public void reset() {
            Arrays.fill(this.live, false);
            this.lastNanos = 0;
        }
    }

    private final Vector3f head = new Vector3f(), animTail = new Vector3f(), a = new Vector3f(), b = new Vector3f();
    private final Vector3f p = new Vector3f(), q = new Vector3f();
    private final Quaternionf rot = new Quaternionf();
    private final Matrix4f s = new Matrix4f();

    /**
     * world: camera-relative joint matrices as the animation (server bones, clips) left them, parents first. Changed in
     * place. tails: per joint, the spring tail in the joint's rest space (NaN x = no spring possible).
     */
    public void apply(RigDef def, float[] tails, Matrix4f[] world, State st, double camX, double camY, double camZ, long now) {
        List<RigDef.Joint> joints = def.joints;
        int n = joints.size();
        float dt = st.lastNanos == 0 ? 0.0F : (now - st.lastNanos) / 1.0e9F;
        st.lastNanos = now;
        if (dt > 0.5F) {
            // not drawn for a while (culled, out of range): start from the animated pose again
            Arrays.fill(st.live, false);
            dt = 0.0F;
        }
        st.accum += Math.min(dt, 0.1F);
        int steps = (int) (st.accum / STEP);
        st.accum -= steps * STEP;
        steps = Math.min(steps, 6);
        float alpha = st.accum / STEP;

        // 1. where the animation puts every spring's head and tail (world space)
        for (int j = 0; j < n; j++) {
            st.snapshot[j].set(world[j]);
            st.corrected[j] = false;
            RigDef.Joint jt = joints.get(j);
            if (!springs(jt, tails, j)) {
                continue;
            }
            Matrix4f w = world[j];
            w.getTranslation(this.head);
            w.transformPosition(tails[j * 3], tails[j * 3 + 1], tails[j * 3 + 2], this.animTail);
            int o = j * 3;
            st.animHead[o] = this.head.x + camX;
            st.animHead[o + 1] = this.head.y + camY;
            st.animHead[o + 2] = this.head.z + camZ;
            st.animTail[o] = this.animTail.x + camX;
            st.animTail[o + 1] = this.animTail.y + camY;
            st.animTail[o + 2] = this.animTail.z + camZ;
            st.length[j] = this.animTail.distance(this.head);
            boolean teleported = st.live[j] && Math.abs(st.animHead[o] - st.lastHead[o]) + Math.abs(st.animHead[o + 1] - st.lastHead[o + 1])
                + Math.abs(st.animHead[o + 2] - st.lastHead[o + 2]) > 4.0;
            if (!st.live[j] || teleported) {
                for (int k = 0; k < 3; k++) {
                    st.cur[o + k] = st.prev[o + k] = st.animTail[o + k];
                }
                st.live[j] = true;
            }
            System.arraycopy(st.animHead, o, st.lastHead, o, 3);
        }

        // 2. simulate
        for (int step = 0; step < steps; step++) {
            for (int j = 0; j < n; j++) {
                RigDef.Joint jt = joints.get(j);
                if (!springs(jt, tails, j)) {
                    continue;
                }
                RigDef.Spring sp = jt.spring;
                int o = j * 3;
                int hp = headParticle(def, tails, j);
                double[] headSrc = hp >= 0 ? st.next : st.animHead;
                int ho = hp >= 0 ? hp * 3 : o;
                for (int k = 0; k < 3; k++) {
                    double target = headSrc[ho + k] + (st.animTail[o + k] - st.animHead[o + k]);
                    double v = (st.cur[o + k] - st.prev[o + k]) * (1.0F - sp.drag());
                    st.next[o + k] = st.cur[o + k] + v + (target - st.cur[o + k]) * sp.stiffness();
                }
                st.next[o + 1] -= sp.gravity() * STEP * STEP;
            }
            for (int round = 0; round < ROUNDS; round++) {
                for (RigDef.Link link : def.links) {
                    int ao = link.a() * 3, bo = link.b() * 3;
                    if (!st.live[link.a()] || !st.live[link.b()]) {
                        continue;
                    }
                    double rx = st.animTail[ao] - st.animTail[bo], ry = st.animTail[ao + 1] - st.animTail[bo + 1], rz = st.animTail[ao + 2] - st.animTail[bo + 2];
                    double rest = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    double dx = st.next[ao] - st.next[bo], dy = st.next[ao + 1] - st.next[bo + 1], dz = st.next[ao + 2] - st.next[bo + 2];
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d < 1.0e-9) {
                        continue;
                    }
                    double f = (d - rest) / d * 0.5 * link.stiffness();
                    st.next[ao] -= dx * f;
                    st.next[ao + 1] -= dy * f;
                    st.next[ao + 2] -= dz * f;
                    st.next[bo] += dx * f;
                    st.next[bo + 1] += dy * f;
                    st.next[bo + 2] += dz * f;
                }
                for (int j = 0; j < n; j++) {
                    RigDef.Joint jt = joints.get(j);
                    if (!springs(jt, tails, j)) {
                        continue;
                    }
                    this.constrain(def, st, j, headParticle(def, tails, j), camX, camY, camZ);
                }
            }
            System.arraycopy(st.cur, 0, st.prev, 0, st.cur.length);
            for (int j = 0; j < n; j++) {
                if (springs(joints.get(j), tails, j)) {
                    System.arraycopy(st.next, j * 3, st.cur, j * 3, 3);
                }
            }
        }
        if (steps == 0) {
            // keep next valid for heads read below
            System.arraycopy(st.cur, 0, st.next, 0, st.cur.length);
        }

        // 3. turn the joints toward their particles (parents first; joints below a turned one follow it)
        for (int j = 0; j < n; j++) {
            RigDef.Joint jt = joints.get(j);
            int parent = jt.parent;
            if (parent >= 0 && st.corrected[parent]) {
                st.corr[parent].mul(world[j], world[j]);
                st.corr[j].set(st.corr[parent]);
                st.corrected[j] = true;
            }
            if (!springs(jt, tails, j)) {
                continue;
            }
            RigDef.Spring sp = jt.spring;
            Matrix4f w = world[j];
            w.getTranslation(this.head);
            w.transformPosition(tails[j * 3], tails[j * 3 + 1], tails[j * 3 + 2], this.animTail);
            int o = j * 3;
            double rx = st.prev[o] + (st.cur[o] - st.prev[o]) * alpha - camX;
            double ry = st.prev[o + 1] + (st.cur[o + 1] - st.prev[o + 1]) * alpha - camY;
            double rz = st.prev[o + 2] + (st.cur[o + 2] - st.prev[o + 2]) * alpha - camZ;
            this.a.set(this.animTail).sub(this.head);
            this.b.set((float) rx, (float) ry, (float) rz).sub(this.head);
            if (this.a.lengthSquared() < 1.0e-12F || this.b.lengthSquared() < 1.0e-12F) {
                continue;
            }
            this.rot.rotationTo(this.a, this.b);
            if (sp.maxAngle() > 0.0F) {
                float angle = this.rot.angle();
                float max = (float) Math.toRadians(sp.maxAngle());
                if (angle > max) {
                    this.rot.slerp(new Quaternionf(), 1.0F - max / angle);
                }
            }
            if (DEBUG != null && DEBUG.equals(jt.name) && (debugCount++ % 240) == 0) {
                kr.gcb.remoterenderer.RemoteRenderer.LOG.info(String.format(java.util.Locale.ROOT,
                    "RR_SPRING %s head=%.3f,%.3f,%.3f anim=%.3f,%.3f,%.3f sim=%.3f,%.3f,%.3f angle=%.1f steps=%d corrected=%s",
                    jt.name, this.head.x, this.head.y, this.head.z, this.animTail.x, this.animTail.y, this.animTail.z, rx, ry, rz,
                    Math.toDegrees(this.rot.angle()), steps, st.corrected[j]));
            }
            this.s.translation(this.head).rotate(this.rot).translate(-this.head.x, -this.head.y, -this.head.z);
            this.s.mul(world[j], world[j]);
            if (st.corrected[j]) {
                this.s.mul(st.corr[j], st.corr[j]);
            } else {
                st.corr[j].set(this.s);
            }
            st.corrected[j] = true;
        }
    }

    private static boolean springs(RigDef.Joint jt, float[] tails, int j) {
        return jt.spring != null && !Float.isNaN(tails[j * 3]);
    }

    /** The particle a spring's head sits on: its parent's, when the parent is a spring too (a chain), else -1. */
    private static int headParticle(RigDef def, float[] tails, int j) {
        int parent = def.joints.get(j).parent;
        return parent >= 0 && springs(def.joints.get(parent), tails, parent) ? parent : -1;
    }

    /** Length from the head, then the colliders (and the length once more). */
    private void constrain(RigDef def, State st, int j, int headParticle, double camX, double camY, double camZ) {
        int o = j * 3;
        double[] headSrc = headParticle >= 0 ? st.next : st.animHead;
        int ho = headParticle >= 0 ? headParticle * 3 : o;
        double hx = headSrc[ho], hy = headSrc[ho + 1], hz = headSrc[ho + 2];
        double length = st.length[j];
        keepLength(st.next, o, hx, hy, hz, length);
        RigDef.Spring sp = def.joints.get(j).spring;
        for (RigDef.Collider c : def.colliders) {
            Matrix4f cw = st.snapshot[c.joint()];
            cw.transformPosition(c.from(), this.p);
            cw.transformPosition(c.to(), this.q);
            float cs = (float) Math.cbrt(Math.abs(cw.determinant3x3()));
            float scale = cs;
            double r = c.radius() * cs + sp.radius() * scale;
            double ax = this.p.x + camX, ay = this.p.y + camY, az = this.p.z + camZ;
            double bx = this.q.x - this.p.x, by = this.q.y - this.p.y, bz = this.q.z - this.p.z;
            double bb = bx * bx + by * by + bz * bz;
            // a joint that starts inside the capsule (a skirt's first ring at the hips) is not pushed by it: pushing
            // only its tail out would fold it upwards
            double th = bb < 1.0e-12 ? 0.0 : Math.clamp(((hx - ax) * bx + (hy - ay) * by + (hz - az) * bz) / bb, 0.0, 1.0);
            double hdx = hx - (ax + bx * th), hdy = hy - (ay + by * th), hdz = hz - (az + bz * th);
            if (hdx * hdx + hdy * hdy + hdz * hdz < r * r) {
                continue;
            }
            double nx = st.next[o], ny = st.next[o + 1], nz = st.next[o + 2];
            double t = bb < 1.0e-12 ? 0.0 : Math.clamp(((nx - ax) * bx + (ny - ay) * by + (nz - az) * bz) / bb, 0.0, 1.0);
            double cx = ax + bx * t, cy = ay + by * t, cz = az + bz * t;
            double ex = nx - cx, ey = ny - cy, ez = nz - cz, e = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (e < r && e > 1.0e-9) {
                st.next[o] = cx + ex / e * r;
                st.next[o + 1] = cy + ey / e * r;
                st.next[o + 2] = cz + ez / e * r;
                keepLength(st.next, o, hx, hy, hz, length);
            }
        }
    }

    private static void keepLength(double[] pts, int o, double hx, double hy, double hz, double length) {
        double dx = pts[o] - hx, dy = pts[o + 1] - hy, dz = pts[o + 2] - hz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > 1.0e-9) {
            pts[o] = hx + dx / d * length;
            pts[o + 1] = hy + dy / d * length;
            pts[o + 2] = hz + dz / d * length;
        }
    }

    /**
     * The tail of every spring joint in its own rest space: the spring's own tail, else its first child joint, else the
     * vertex farthest from it (geometry), else none (NaN).
     */
    public static float[] tails(RigDef def, float[] jointFar) {
        int n = def.joints.size();
        float[] out = new float[n * 3];
        Arrays.fill(out, Float.NaN);
        Vector3f v = new Vector3f();
        for (int j = 0; j < n; j++) {
            RigDef.Joint jt = def.joints.get(j);
            if (jt.spring == null) {
                continue;
            }
            if (jt.spring.tail() != null) {
                v.set(jt.spring.tail());
            } else {
                int child = -1;
                for (int c = j + 1; c < n; c++) {
                    if (def.joints.get(c).parent == j) {
                        child = c;
                        break;
                    }
                }
                if (child >= 0 && def.joints.get(child).local.getTranslation(new Vector3f()).lengthSquared() > 1.0e-10F) {
                    def.joints.get(child).local.getTranslation(v);
                } else if (!Float.isNaN(jointFar[j * 3])) {
                    // the vertices are in bind pose: into the joint's space by its inverse bind matrix
                    jt.inverseBind.transformPosition(jointFar[j * 3], jointFar[j * 3 + 1], jointFar[j * 3 + 2], v);
                } else {
                    continue;
                }
            }
            out[j * 3] = v.x;
            out[j * 3 + 1] = v.y;
            out[j * 3 + 2] = v.z;
        }
        return out;
    }
}
