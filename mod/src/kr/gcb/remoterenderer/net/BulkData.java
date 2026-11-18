package kr.gcb.remoterenderer.net;

import java.util.ArrayList;
import java.util.List;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.Stats;
import kr.gcb.remoterenderer.mixin.DisplayDataAccessor;
import kr.gcb.remoterenderer.mixin.EntityFlagsAccessor;
import kr.gcb.remoterenderer.mixin.ItemDisplayDataAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The bone bulk channel of ModelEngine (R4.1.0+) and BetterModel (3.3.0+): {@code modelengine:bulk_data}, the protocol of the
 * MEG client mod. A client that registers the channel gets one payload per model and tick instead of one entity data packet
 * per bone; the plugins decide per player and keep sending the vanilla packets to everybody else.
 *
 * <p>Payload: byte type (0 = bulk data), varint entry count, then per entry: varint entity id, byte mask and the fields of
 * the set bits in this order: 1 translation, 2 left rotation, 4 scale, 8 right rotation (half floats), 16 transformation
 * interpolation duration (varint), 32 shared entity flags (byte) + glow colour (int), 64 brightness (int),
 * 128 billboard (byte) + view range (float) + item display context (byte). A transformation field also starts the
 * interpolation on that tick, as the plugins' vanilla packets do with "interpolation delay 0".
 *
 * <p>Entries are applied as the vanilla entity data packet would be (SynchedEntityData.assignValues) on the game thread, in
 * the order the packets arrived, so everything that reads display entities sees the same thing either way.
 *
 * <p>{@code gcb:bulk_data} (the GCB-patched ModelEngine, full floats) belongs to the GCB mod and is left to it: that
 * ModelEngine prefers it over this channel when a client registers both.
 */
public final class BulkData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("modelengine", "bulk_data");
    public static final CustomPacketPayload.Type<Payload> TYPE = new CustomPacketPayload.Type<>(ID);
    /** A mod that already reads this channel (two registrations of one payload type stop the game). */
    private static final String MEG_CLIENT_MOD = "megclientmod";
    private static final int FLOATS = 15, INTS = 6;
    private static final Payload EMPTY = new Payload(0, new int[0], new int[0], new float[0], new int[0]);
    private static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec((payload, buf) -> {
        // the client never sends it
    }, BulkData::read);

    private static boolean registered;
    private static boolean warnedType, warnedMalformed, warnedApply;

    private BulkData() {
    }

    /** Client init (only with Fabric API's networking module). The channel itself is announced per connection, see {@link #onInit}. */
    public static void register() {
        if (FabricLoader.getInstance().isModLoaded(MEG_CLIENT_MOD)) {
            RemoteRenderer.LOG.info("RemoteRenderer: the MEG client mod reads modelengine:bulk_data, RemoteRenderer leaves the channel to it");
            return;
        }
        if (!RemoteRenderer.config.bulkData) {
            RemoteRenderer.LOG.info("RemoteRenderer: bulkData is off, modelengine:bulk_data not registered");
            return;
        }
        try {
            PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        } catch (IllegalArgumentException e) {
            RemoteRenderer.LOG.info("RemoteRenderer: modelengine:bulk_data is already registered by another mod, left to it ({})", e.getMessage());
            return;
        }
        registered = true;
        ClientPlayConnectionEvents.INIT.register((handler, client) -> onInit());
    }

    /** A new server connection: announce the channel (Fabric sends the registered channels when the server is ready). */
    private static void onInit() {
        if (registered && RemoteRenderer.config.bulkData) {
            ClientPlayNetworking.registerReceiver(TYPE, BulkData::receive);
        }
    }

    public static boolean registered() {
        return registered;
    }

    /**
     * Test aid: apply through Minecraft.execute as the MEG client mod does, which runs the entries after packets that came
     * in later (in a frame that handles more than one tick), to compare with applying them in order.
     */
    private static final boolean DEFER = Boolean.getBoolean("remoterenderer.bulkDefer");

    private static void receive(Payload payload, ClientPlayNetworking.Context context) {
        Minecraft mc = context.client();
        if (mc.isSameThread() && !DEFER) {
            apply(mc.level, payload);
        } else {
            mc.execute(() -> apply(mc.level, payload));
        }
    }

    // ---- decoding (network thread) ----

    public record Payload(int count, int[] ids, int[] masks, float[] floats, int[] ints) implements CustomPacketPayload {
        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    private static Payload read(RegistryFriendlyByteBuf buf) {
        try {
            int type = buf.readByte();
            if (type != 0) {
                if (!warnedType) {
                    warnedType = true;
                    RemoteRenderer.LOG.warn("RemoteRenderer: modelengine:bulk_data packet type {} is unknown, ignored", type);
                }
                buf.skipBytes(buf.readableBytes());
                return EMPTY;
            }
            int count = buf.readVarInt();
            // an entry takes at least two bytes (id, mask)
            if (count < 0 || count > buf.readableBytes() / 2) {
                throw new IllegalArgumentException("entry count " + count + " for " + buf.readableBytes() + " bytes");
            }
            int[] ids = new int[count], masks = new int[count], ints = new int[count * INTS];
            float[] floats = new float[count * FLOATS];
            for (int i = 0; i < count; i++) {
                ids[i] = buf.readVarInt();
                int mask = buf.readUnsignedByte();
                masks[i] = mask;
                int f = i * FLOATS, n = i * INTS;
                if ((mask & 1) != 0) {
                    halves(buf, floats, f, 3);
                }
                if ((mask & 2) != 0) {
                    halves(buf, floats, f + 3, 4);
                }
                if ((mask & 4) != 0) {
                    halves(buf, floats, f + 7, 3);
                }
                if ((mask & 8) != 0) {
                    halves(buf, floats, f + 10, 4);
                }
                if ((mask & 16) != 0) {
                    ints[n] = buf.readVarInt();
                }
                if ((mask & 32) != 0) {
                    ints[n + 1] = buf.readByte();
                    ints[n + 2] = buf.readInt();
                }
                if ((mask & 64) != 0) {
                    ints[n + 3] = buf.readInt();
                }
                if ((mask & 128) != 0) {
                    ints[n + 4] = buf.readByte();
                    floats[f + 14] = buf.readFloat();
                    ints[n + 5] = buf.readByte();
                }
            }
            // anything after the entries would be from a newer format: what was read stays valid
            buf.skipBytes(buf.readableBytes());
            return new Payload(count, ids, masks, floats, ints);
        } catch (RuntimeException e) {
            // a broken packet must not end the connection; the bones keep their last pose
            if (!warnedMalformed) {
                warnedMalformed = true;
                RemoteRenderer.LOG.warn("RemoteRenderer: malformed modelengine:bulk_data packet ignored: {}", e.toString());
            }
            buf.skipBytes(buf.readableBytes());
            return EMPTY;
        }
    }

    private static void halves(RegistryFriendlyByteBuf buf, float[] out, int at, int n) {
        for (int i = 0; i < n; i++) {
            out[at + i] = Float.float16ToFloat(buf.readShort());
        }
    }

    // ---- applying (game thread) ----

    static void apply(@Nullable ClientLevel level, Payload p) {
        Stats stats = RemoteRenderer.STATS;
        stats.bulkPayloads++;
        if (level == null) {
            return;
        }
        for (int i = 0; i < p.count; i++) {
            Entity entity = level.getEntity(p.ids[i]);
            if (!(entity instanceof Display display)) {
                stats.bulkMissing++;
                continue;
            }
            List<DataValue<?>> values = values(display, p, i);
            if (values.isEmpty()) {
                continue;
            }
            try {
                display.getEntityData().assignValues(values);
                stats.bulkEntries++;
            } catch (RuntimeException e) {
                if (!warnedApply) {
                    warnedApply = true;
                    RemoteRenderer.LOG.warn("RemoteRenderer: modelengine:bulk_data entry for entity {} not applied: {}", p.ids[i], e.toString());
                }
            }
        }
    }

    private static List<DataValue<?>> values(Display display, Payload p, int i) {
        int m = p.masks[i], f = i * FLOATS, n = i * INTS;
        float[] fl = p.floats;
        List<DataValue<?>> v = new ArrayList<>(8);
        if ((m & 0x0F) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$startDelta(), 0));
        }
        if ((m & 1) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$translation(), new Vector3f(fl[f], fl[f + 1], fl[f + 2])));
        }
        if ((m & 2) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$leftRotation(), rotation(fl, f + 3)));
        }
        if ((m & 4) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$scale(), new Vector3f(fl[f + 7], fl[f + 8], fl[f + 9])));
        }
        if ((m & 8) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$rightRotation(), rotation(fl, f + 10)));
        }
        if ((m & 16) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$duration(), p.ints[n]));
        }
        if ((m & 32) != 0) {
            v.add(DataValue.create(EntityFlagsAccessor.remoterenderer$sharedFlags(), (byte) p.ints[n + 1]));
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$glowColor(), p.ints[n + 2]));
        }
        if ((m & 64) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$brightness(), p.ints[n + 3]));
        }
        if ((m & 128) != 0) {
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$billboard(), (byte) p.ints[n + 4]));
            v.add(DataValue.create(DisplayDataAccessor.remoterenderer$viewRange(), fl[f + 14]));
            // the item display context exists on item displays only (index 24 is something else on text displays)
            if (display instanceof Display.ItemDisplay) {
                v.add(DataValue.create(ItemDisplayDataAccessor.remoterenderer$itemDisplay(), (byte) p.ints[n + 5]));
            }
        }
        return v;
    }

    /** Half floats leave a rotation slightly off unit length (a little scale and skew): normalized, as the sender meant it. */
    private static Quaternionf rotation(float[] fl, int at) {
        Quaternionf q = new Quaternionf(fl[at], fl[at + 1], fl[at + 2], fl[at + 3]);
        float len = q.lengthSquared();
        return len > 1.0e-12F && Float.isFinite(len) ? q.normalize() : q.identity();
    }
}
