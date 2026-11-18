package kr.gcb.remoterenderer.duck;

import net.minecraft.client.renderer.item.ItemModelResolver;

/** Added to ItemDisplayRenderer: its item model resolver, for the fast entity path's extraction. */
public interface RrItemDisplayRenderer {
    ItemModelResolver remoterenderer$resolver();
}
