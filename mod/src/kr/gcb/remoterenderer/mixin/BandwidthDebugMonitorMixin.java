package kr.gcb.remoterenderer.mixin;

import kr.gcb.remoterenderer.Stats;
import net.minecraft.network.BandwidthDebugMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Received frames and bytes of the server connection (what the F3 network chart counts), for the stats line. Network thread. */
@Mixin(BandwidthDebugMonitor.class)
public abstract class BandwidthDebugMonitorMixin {
    @Inject(method = "onReceive(I)V", at = @At("HEAD"))
    private void remoterenderer$onReceive(int bytes, CallbackInfo ci) {
        Stats.NET_BYTES.add(bytes);
        Stats.NET_FRAMES.increment();
    }
}
