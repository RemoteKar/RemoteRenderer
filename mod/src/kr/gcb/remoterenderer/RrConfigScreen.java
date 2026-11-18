package kr.gcb.remoterenderer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import kr.gcb.remoterenderer.mesh.MeshCache;
import kr.gcb.remoterenderer.rig.RigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The settings screen (Mod Menu's config button, or /remoterenderer menu). Saved to config/remoterenderer.properties on close. */
public final class RrConfigScreen extends Screen {
    private final @Nullable Screen parent;
    private boolean dirtyMeshes;
    private boolean dirtyShaders;
    private boolean compact;

    public RrConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("remoterenderer.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        RrConfig c = RemoteRenderer.config;
        // compact on small screens (large GUI scale): 8 rows, no status lines
        this.compact = this.height < 320;
        int w = 150, h = 20, gap = this.compact ? 2 : 4;
        int left = this.width / 2 - w - 5, right = this.width / 2 + 5;
        int y0 = this.compact ? 32 : 40;
        // left: drawing displays on the GPU
        int y = y0;
        this.toggle(left, y, w, h, "enabled", c.enabled, v -> RemoteRenderer.setEnabled(v));
        y += h + gap;
        this.toggle(left, y, w, h, "objmc", c.objmc, v -> {
            c.objmc = v;
            this.dirtyMeshes = true;
        });
        y += h + gap;
        this.toggle(left, y, w, h, "cacheResolve", c.cacheResolve, v -> {
            c.cacheResolve = v;
            this.dirtyMeshes = true;
        });
        y += h + gap;
        this.toggle(left, y, w, h, "cullOffscreen", c.cullOffscreen, v -> c.cullOffscreen = v);
        y += h + gap;
        this.toggle(left, y, w, h, "fastEntities", c.fastEntities, v -> c.fastEntities = v);
        y += h + gap;
        // off / on / on without model shadows
        this.choice(left, y, w, h, "shaderCompat", List.of(0, 1, 2), !c.shaderCompat ? 0 : c.compatShadows ? 1 : 2,
            v -> v == 0 ? CommonComponents.OPTION_OFF : v == 1 ? CommonComponents.OPTION_ON : Component.translatable("remoterenderer.menu.shaderCompat.noShadows"), v -> {
                if (c.shaderCompat != (v != 0)) {
                    this.dirtyMeshes = true;
                }
                c.shaderCompat = v != 0;
                c.compatShadows = v != 2;
            });
        y += h + gap;
        this.toggle(left, y, w, h, "adaptCustomItemShaders", c.adaptCustomItemShaders, v -> {
            c.adaptCustomItemShaders = v;
            this.dirtyShaders = true;
        });
        y += h + gap;
        this.choice(left, y, w, h, "hideWhileBakingQuads", List.of(0, 256, 512, 1024, 4096), c.hideWhileBakingQuads,
            v -> v == 0 ? CommonComponents.OPTION_OFF : Component.literal(v + "+"), v -> c.hideWhileBakingQuads = v);

        // right: rigs
        y = y0;
        this.toggle(right, y, w, h, "rigs", c.rigs, v -> c.rigs = v);
        y += h + gap;
        this.toggle(right, y, w, h, "rigPhysics", c.rigPhysics, v -> c.rigPhysics = v);
        y += h + gap;
        this.choice(right, y, w, h, "rigTextureMaxSize", List.of(0, 512, 1024, 2048, 4096), c.rigTextureMaxSize,
            v -> v == 0 ? Component.translatable("remoterenderer.menu.original") : Component.literal(Integer.toString(v)), v -> {
                if (c.rigTextureMaxSize != v) {
                    c.rigTextureMaxSize = v;
                    RigManager.dropTextures();
                }
            });
        y += h + gap;
        this.choice(right, y, w, h, "rigTextureIdleSeconds", List.of(10, 30, 60, 120, 300), c.rigTextureIdleSeconds,
            v -> Component.literal(v + "s"), v -> c.rigTextureIdleSeconds = v);
        y += h + gap;
        this.choice(right, y, w, h, "bakeThreads", List.of(1, 2, 3, 4, 6, 8), c.bakeThreads, v -> Component.literal(Integer.toString(v)),
            v -> c.bakeThreads = v);
        y += h + gap;
        this.choice(right, y, w, h, "statsLogSeconds", List.of(0, 1, 2, 5, 10, 30), c.statsLogSeconds,
            v -> v == 0 ? CommonComponents.OPTION_OFF : Component.literal(v + "s"), v -> c.statsLogSeconds = v);
        y += h + gap;
        // two buttons in one row
        int half = (w - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("remoterenderer.menu.clear"), b -> MeshCache.requestClear())
            .bounds(right, y, half, h).tooltip(Tooltip.create(Component.translatable("remoterenderer.menu.clear.tip"))).build());
        this.addRenderableWidget(Button.builder(Component.translatable("remoterenderer.menu.reload"), b -> {
            this.save();
            Minecraft.getInstance().reloadResourcePacks();
        }).bounds(right + w - half, y, half, h).tooltip(Tooltip.create(Component.translatable("remoterenderer.menu.reload.tip"))).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 100, this.height - (this.compact ? 24 : 28), 200, 20).build());
    }

    private void toggle(int x, int y, int w, int h, String key, boolean value, Consumer<Boolean> set) {
        this.addRenderableWidget(CycleButton.onOffBuilder(value)
            .withTooltip(v -> Tooltip.create(Component.translatable("remoterenderer.menu." + key + ".tip")))
            .create(x, y, w, h, Component.translatable("remoterenderer.menu." + key), (b, v) -> set.accept(v)));
    }

    private void choice(int x, int y, int w, int h, String key, List<Integer> values, int current, Function<Integer, Component> label, Consumer<Integer> set) {
        Integer start = values.contains(current) ? current : values.getFirst();
        this.addRenderableWidget(CycleButton.builder(label, start).withValues(values)
            .withTooltip(v -> Tooltip.create(Component.translatable("remoterenderer.menu." + key + ".tip")))
            .create(x, y, w, h, Component.translatable("remoterenderer.menu." + key), (b, v) -> set.accept(v)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, this.compact ? 6 : 12, 0xFFFFFFFF);
        g.centeredText(this.font, Component.translatable("remoterenderer.menu.displays"), this.width / 2 - 80, this.compact ? 20 : 28, 0xFFA0A0A0);
        g.centeredText(this.font, Component.translatable("remoterenderer.menu.rigsHeader"), this.width / 2 + 80, this.compact ? 20 : 28, 0xFFA0A0A0);
        Stats s = RemoteRenderer.STATS;
        String state;
        if (RemoteRenderer.active()) {
            state = Component.translatable("remoterenderer.menu.state.on").getString() + (RemoteRenderer.compat()
                ? " - " + Component.translatable("remoterenderer.menu.state.shaderCompat").getString() : "") + (kr.gcb.remoterenderer.render.PackShaders.active() && !RemoteRenderer.compat()
                ? " - " + Component.translatable("remoterenderer.menu.state.packShader", String.valueOf(RemoteRenderer.customItemShader())).getString() : "");
        } else {
            String reason = RemoteRenderer.disabledReason();
            if (reason == null && RemoteRenderer.customItemShader() != null && !RemoteRenderer.config.ignoreCustomItemShaders) {
                reason = Component.translatable("remoterenderer.menu.state.customShader", RemoteRenderer.customItemShader()).getString()
                    + (kr.gcb.remoterenderer.render.PackShaders.failure() != null ? " (" + kr.gcb.remoterenderer.render.PackShaders.failure() + ")" : "");
            }
            state = Component.translatable("remoterenderer.menu.state.off").getString() + (reason != null ? " - " + reason : "");
        }
        if (this.compact) {
            return;
        }
        int y = this.height - 70;
        g.centeredText(this.font, state, this.width / 2, y, RemoteRenderer.active() ? 0xFF80FF80 : 0xFFFFC060);
        g.centeredText(this.font, Component.translatable("remoterenderer.menu.stats", this.minecraft.getFps(), String.format(java.util.Locale.ROOT, "%.2f", s.avgFrameMs),
            s.lastInstances, s.lastDraws, s.lastMeshCulled).getString(), this.width / 2, y + 12, 0xFFD0D0D0);
        g.centeredText(this.font, Component.translatable("remoterenderer.menu.rigStats", RigManager.rigs().size(), s.lastRigInstances, s.lastRigCulled,
            String.format(java.util.Locale.ROOT, "%.1f", RigManager.textureBytes() / 1048576.0)).getString(), this.width / 2, y + 24, 0xFFD0D0D0);
    }

    private void save() {
        if (RemoteRenderer.configFile != null) {
            RemoteRenderer.config.save(RemoteRenderer.configFile);
        }
    }

    @Override
    public void onClose() {
        this.save();
        if (this.dirtyMeshes) {
            MeshCache.requestClear();
        }
        if (this.dirtyShaders) {
            RemoteRenderer.adaptItemShader();
        }
        this.minecraft.gui.setScreen(this.parent);
    }
}
