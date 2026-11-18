package kr.gcb.remoterenderer.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Display's entity data keys (private), for applying bulk bone data as the vanilla packet would. */
@Mixin(Display.class)
public interface DisplayDataAccessor {
    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
    static EntityDataAccessor<Integer> remoterenderer$startDelta() {
        throw new AssertionError();
    }

    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID")
    static EntityDataAccessor<Integer> remoterenderer$duration() {
        throw new AssertionError();
    }

    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> remoterenderer$translation() {
        throw new AssertionError();
    }

    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3fc> remoterenderer$scale() {
        throw new AssertionError();
    }

    @Accessor("DATA_LEFT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> remoterenderer$leftRotation() {
        throw new AssertionError();
    }

    @Accessor("DATA_RIGHT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> remoterenderer$rightRotation() {
        throw new AssertionError();
    }

    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> remoterenderer$billboard() {
        throw new AssertionError();
    }

    @Accessor("DATA_BRIGHTNESS_OVERRIDE_ID")
    static EntityDataAccessor<Integer> remoterenderer$brightness() {
        throw new AssertionError();
    }

    @Accessor("DATA_VIEW_RANGE_ID")
    static EntityDataAccessor<Float> remoterenderer$viewRange() {
        throw new AssertionError();
    }

    @Accessor("DATA_GLOW_COLOR_OVERRIDE_ID")
    static EntityDataAccessor<Integer> remoterenderer$glowColor() {
        throw new AssertionError();
    }
}
