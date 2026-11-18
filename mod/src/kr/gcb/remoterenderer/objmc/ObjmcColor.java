package kr.gcb.remoterenderer.objmc;

import org.joml.Matrix3f;

/**
 * objmc reads its controls from the item's tint colour ("colorbehavior": one 3 bit behaviour per colour byte -
 * pitch / yaw / roll rotation, animation time, scale, hue, hurt tint; 219 = the whole colour is a frame number).
 * Same arithmetic as objmc_main.glsl, done once per instance on the CPU.
 */
public final class ObjmcColor {
    public float rotX, rotY, rotZ;
    public float scale;
    public float timeColor;
    public boolean autoplay;
    public float overlayR, overlayG, overlayB;

    public ObjmcColor decode(ObjmcModel model, int argb) {
        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
        this.rotX = this.rotY = this.rotZ = 0.0F;
        this.scale = 1.0F;
        this.timeColor = 0.0F;
        this.autoplay = model.autoplay;
        this.overlayR = this.overlayG = this.overlayB = 1.0F;
        int cb = model.colorBehavior;
        if (cb == 219) {
            // frames 0-8388607; (int(Color.r*255)*65536) % 32768 is always 0 in the original
            this.timeColor = g * 256 + b;
            this.autoplay = r <= 127;
            return this;
        }
        double[] rot = new double[3];
        double[] acc = {255.0 / 256.0, 255.0 / 256.0, 255.0 / 256.0};
        double scaleValue = 0.0, scaleDiv = 255.0 / 256.0, hueValue = 0.0, hueDiv = 255.0 / 256.0;
        long time = 0;
        int[] channels = {r, g, b};
        int[] behaviours = {cb >> 6 & 7, cb >> 3 & 7, cb & 7};
        for (int i = 0; i < 3; i++) {
            int c = channels[i];
            switch (behaviours[i]) {
                case 0, 1, 2 -> {
                    int axis = behaviours[i];
                    rot[axis] = rot[axis] * 256.0 + c;
                    acc[axis] *= 256.0;
                }
                case 3 -> time = time * 256 + c;
                case 4 -> {
                    scaleValue = scaleValue * 256.0 + c;
                    scaleDiv *= 256.0;
                }
                case 5 -> {
                    hueValue = hueValue * 256.0 + c;
                    hueDiv *= 256.0;
                }
                case 6 -> {
                    if (c != 0) {
                        this.overlayR = 1.0F;
                        this.overlayG = 0.7F;
                        this.overlayB = 0.7F;
                    }
                }
                default -> {
                }
            }
        }
        double twoPi = Math.PI * 2.0;
        this.rotX = (float) (rot[0] / acc[0] * twoPi);
        this.rotY = (float) (rot[1] / acc[1] * twoPi);
        this.rotZ = (float) (rot[2] / acc[2] * twoPi);
        if (scaleValue > 0.0) {
            this.scale = (float) (scaleValue / scaleDiv);
        }
        if (hueValue > 0.0) {
            float h = (float) (hueValue / hueDiv);
            this.overlayR = hue(h + 1.0F);
            this.overlayG = hue(h + 2.0F / 3.0F);
            this.overlayB = hue(h + 1.0F / 3.0F);
        }
        this.timeColor = time;
        return this;
    }

    /** One channel of objmc's hrgb(). */
    private static float hue(float k) {
        float f = k - (float) Math.floor(k);
        float p = Math.abs(f * 6.0F - 3.0F);
        return Math.clamp(p - 1.0F, 0.0F, 1.0F);
    }

    /** objmc's rotate() ("3d rotation matrix from Barf Creations"), GLSL column order. */
    public Matrix3f rotation(Matrix3f dest) {
        float sx = (float) Math.sin(this.rotX), cx = (float) Math.cos(this.rotX);
        float sy = (float) Math.sin(-this.rotY), cy = (float) Math.cos(-this.rotY);
        float sz = (float) Math.sin(-this.rotZ), cz = (float) Math.cos(-this.rotZ);
        return dest.set(
            -sx * sy * sz + cy * cz, -cx * sz, -sx * cy * sz - sy * cz,
            sx * sy * cz + cy * sz, cx * cz, -sy * sz + sx * cy * cz,
            cx * sy, -sx, cx * cy
        );
    }
}
