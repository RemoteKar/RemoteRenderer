package kr.gcb.remoterenderer;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's "config" button (only loaded when Mod Menu is installed). */
public final class RrModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RrConfigScreen::new;
    }
}
