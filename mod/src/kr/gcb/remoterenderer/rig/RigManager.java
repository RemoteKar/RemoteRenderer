package kr.gcb.remoterenderer.rig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kr.gcb.remoterenderer.RemoteRenderer;
import kr.gcb.remoterenderer.mesh.MeshEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.jspecify.annotations.Nullable;

/**
 * The rigs of the loaded resource packs (assets/<namespace>/remoterenderer/rigs/<name>.json) plus the ones mods
 * registered through the API. Looked up by the item_model of an item display's item.
 */
public final class RigManager {
    public static final String DIRECTORY = "remoterenderer/rigs";

    public enum State { LOADING, READY, FAILED }

    /** A rig with its runtime resources. */
    public static final class Rig {
        public final RigDef def;
        public final String source;
        volatile State state = State.LOADING;
        volatile @Nullable String error;
        private @Nullable RigGeometry mesh;
        private List<RigTexture> textures = List.of();
        /** "bones" geometry, by the bone models it was built from */
        private final Map<String, RigGeometry> fromBones = new HashMap<>();
        private final Map<String, Long> fromBonesUsed = new HashMap<>();
        /** instances drawn this frame / the last frame */
        public int instances, lastInstances;

        Rig(RigDef def, String source) {
            this.def = def;
            this.source = source;
            if (def.boneGeometry()) {
                this.state = State.READY;
            }
        }

        public State state() {
            return this.state;
        }

        public @Nullable String error() {
            return this.error;
        }

        public @Nullable RigGeometry meshGeometry() {
            return this.state == State.READY ? this.mesh : null;
        }

        /** "bones" geometry for these bone models (render thread). */
        public @Nullable RigGeometry boneGeometry(MeshEntry[] entries, long now) {
            StringBuilder key = new StringBuilder();
            for (MeshEntry e : entries) {
                key.append(e == null ? -1 : e.id).append(',');
            }
            String k = key.toString();
            RigGeometry g = this.fromBones.get(k);
            if (g == null || g.isClosed()) {
                g = RigGeometry.fromBones(this.def, entries);
                if (g == null) {
                    return null;
                }
                this.fromBones.put(k, g);
            }
            this.fromBonesUsed.put(k, now);
            return g;
        }

        /** Frees "bones" geometry nobody used for a while (render thread, outside a pass). */
        void evict(long now) {
            for (Iterator<Map.Entry<String, Long>> it = this.fromBonesUsed.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > 10_000_000_000L) {
                    RigGeometry g = this.fromBones.remove(e.getKey());
                    if (g != null) {
                        g.close();
                    }
                    it.remove();
                }
            }
        }

        volatile boolean closed;

        void close() {
            this.closed = true;
            if (this.mesh != null) {
                this.mesh.close();
            }
            for (RigTexture t : this.textures) {
                t.close();
            }
            this.fromBones.values().forEach(RigGeometry::close);
            this.fromBones.clear();
        }
    }

    /** Frees every rig texture now (a new size limit); they load again when their models are drawn. */
    public static void dropTextures() {
        for (Rig r : rigs.values()) {
            for (RigTexture t : r.textures) {
                t.evictIfIdle(Long.MAX_VALUE, 0L);
            }
        }
    }

    /** GPU memory of the rig textures loaded right now. */
    public static long textureBytes() {
        long sum = 0;
        for (Rig r : rigs.values()) {
            for (RigTexture t : r.textures) {
                sum += t.gpuBytes();
            }
        }
        return sum;
    }

    public record BoneRef(Rig rig, int bone) {
    }

    private static Map<Identifier, Rig> rigs = Map.of();
    private static Map<Identifier, BoneRef> bones = Map.of();
    private record ModelCmd(Identifier model, float cmd) {
    }
    private static Map<ModelCmd, BoneRef> bonesByCmd = Map.of();
    private static final Map<Identifier, RigDef> REGISTERED = new LinkedHashMap<>();
    private static long lastEvict;

    private RigManager() {
    }

    /** The rig bone this item is, if any. */
    public static @Nullable BoneRef boneOf(ItemStack stack) {
        if (bones.isEmpty() && bonesByCmd.isEmpty()) {
            return null;
        }
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null) {
            return null;
        }
        if (!bonesByCmd.isEmpty()) {
            CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd != null && !cmd.floats().isEmpty()) {
                BoneRef r = bonesByCmd.get(new ModelCmd(model, cmd.floats().getFirst()));
                if (r != null) {
                    return r;
                }
            }
        }
        return bones.get(model);
    }

    /** custom_model_data colour 0 (BetterModel's hit flash tints its bones this way), white if none. */
    public static int tintOf(ItemStack stack) {
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null && !cmd.colors().isEmpty()) {
            return 0xFF000000 | cmd.colors().getFirst();
        }
        return 0xFFFFFFFF;
    }

    /** The server turned this model's physics off: custom_model_data string "rr:physics=off". */
    public static boolean physicsOff(ItemStack stack) {
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return cmd != null && cmd.strings().contains("rr:physics=off");
    }

    /** A clip asked for by the item: custom_model_data string "rr:clip=<name>". */
    public static @Nullable String clipOf(ItemStack stack) {
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null) {
            for (String s : cmd.strings()) {
                if (s.startsWith("rr:clip=")) {
                    return s.substring(8);
                }
            }
        }
        return null;
    }

    public static Collection<Rig> rigs() {
        return rigs.values();
    }

    public static synchronized void register(RigDef def) {
        REGISTERED.put(def.id, def);
    }

    public static synchronized void unregister(Identifier id) {
        REGISTERED.remove(id);
    }

    /** New resource packs (render thread, during the resource reload). */
    public static synchronized void reload(ResourceManager resources) {
        for (Rig r : rigs.values()) {
            r.close();
        }
        Map<Identifier, Rig> next = new LinkedHashMap<>();
        Map<Identifier, Resource> found = resources.listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> e : found.entrySet()) {
            Identifier file = e.getKey();
            String path = file.getPath();
            Identifier id = Identifier.fromNamespaceAndPath(file.getNamespace(), path.substring(DIRECTORY.length() + 1, path.length() - 5));
            try (Reader r = e.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                next.put(id, new Rig(RigDef.parse(id, json), e.getValue().sourcePackId()));
            } catch (IOException | RuntimeException ex) {
                RemoteRenderer.LOG.warn("Rig {} ({}) not loaded: {}", id, e.getValue().sourcePackId(), ex.toString());
            }
        }
        for (RigDef def : REGISTERED.values()) {
            next.put(def.id, new Rig(def, "api"));
        }
        Map<Identifier, BoneRef> byModel = new HashMap<>();
        Map<ModelCmd, BoneRef> byCmd = new HashMap<>();
        for (Rig rig : next.values()) {
            for (int b = 0; b < rig.def.bones.size(); b++) {
                Identifier model = rig.def.bones.get(b).itemModel();
                float cmd = rig.def.bones.get(b).customModelData();
                if (!Float.isNaN(cmd)) {
                    BoneRef old = byCmd.put(new ModelCmd(model, cmd), new BoneRef(rig, b));
                    if (old != null) {
                        RemoteRenderer.LOG.warn("Item model {} #{} is a bone of both {} and {}; {} wins", model, cmd, old.rig().def.id, rig.def.id, rig.def.id);
                    }
                    continue;
                }
                BoneRef old = byModel.put(model, new BoneRef(rig, b));
                if (old != null) {
                    RemoteRenderer.LOG.warn("Item model {} is a bone of both {} and {}; {} wins", model, old.rig().def.id, rig.def.id, rig.def.id);
                }
            }
            if (!rig.def.boneGeometry()) {
                startLoading(rig, resources);
            }
        }
        rigs = next;
        bones = byModel;
        bonesByCmd = byCmd;
        if (!next.isEmpty()) {
            RemoteRenderer.LOG.info("RemoteRenderer rigs: {}", next.keySet());
        }
    }

    /** Mesh file and textures on a background thread (a 4096 texture with its mips takes a moment). */
    private static void startLoading(Rig rig, ResourceManager resources) {
        RigDef.MeshSpec spec = rig.def.mesh;
        CompletableFuture.runAsync(() -> {
            long t0 = System.nanoTime();
            try {
                ByteBuffer file;
                try (InputStream in = resources.open(spec.data())) {
                    byte[] bytes = in.readAllBytes();
                    file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                }
                for (RigDef.Clip clip : rig.def.clips.values()) {
                    int n = clip.frames * clip.joints.length * 10;
                    float[] data = new float[n];
                    ByteBuffer b = file.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    b.position((int) clip.offset);
                    b.asFloatBuffer().get(data);
                    clip.data = data;
                }
                List<RigTexture> textures = new ArrayList<>();
                Map<Identifier, RigTexture> byId = new HashMap<>();
                for (RigDef.Submesh s : spec.submeshes()) {
                    if (s.texture() == null) {
                        textures.add(null);
                        continue;
                    }
                    RigTexture t = byId.get(s.texture());
                    if (t == null) {
                        t = new RigTexture(s.texture());
                        byId.put(s.texture(), t);
                    }
                    textures.add(t);
                }
                RigGeometry mesh = RigGeometry.fromFile(rig.def, spec, file, textures);
                synchronized (RigManager.class) {
                    if (rig.closed) {
                        // a newer reload replaced this rig meanwhile (nothing of this is on the GPU yet)
                        mesh.close();
                        byId.values().forEach(RigTexture::close);
                        return;
                    }
                    rig.textures = new ArrayList<>(byId.values());
                    rig.mesh = mesh;
                    rig.state = State.READY;
                }
                RemoteRenderer.LOG.info("Rig {} ready: {} vertices, {} triangles, {} joints, {} textures, {} clips ({} ms)", rig.def.id, spec.vertexCount(),
                    spec.indexCount() / 3, rig.def.joints.size(), byId.size(), rig.def.clips.size(), (System.nanoTime() - t0) / 1_000_000);
            } catch (IOException | RuntimeException e) {
                rig.error = e.toString();
                rig.state = State.FAILED;
                RemoteRenderer.LOG.warn("Rig {} failed to load: {}", rig.def.id, e.toString());
            }
        }, Util.backgroundExecutor());
    }

    /** Render thread, once a frame outside a pass. */
    public static void endFrame(long now) {
        if (now - lastEvict < 2_000_000_000L) {
            return;
        }
        lastEvict = now;
        long idle = Math.max(5L, RemoteRenderer.config.rigTextureIdleSeconds) * 1_000_000_000L;
        for (Rig r : rigs.values()) {
            r.evict(now);
            for (RigTexture t : r.textures) {
                t.evictIfIdle(now, idle);
            }
        }
    }
}
