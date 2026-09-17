package kr.urakamza.mcchattts;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Optional integration: only Mod Menu invokes this entrypoint. */
@Environment(EnvType.CLIENT)
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TTSConfigScreen::create;
    }
}
