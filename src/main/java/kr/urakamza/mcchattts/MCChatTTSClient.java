package kr.urakamza.mcchattts;

import kr.urakamza.mcchattts.tts.TTSManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;

public class MCChatTTSClient implements ClientModInitializer {

    public static KeyMapping keySkip;
    public static KeyMapping keyStop;
    public static KeyMapping keyConfig;

    @Override
    public void onInitializeClient() {
        TTSConfig.load();
        TTSManager.init();
        TTSManager.setAccepting(false);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            TTSManager.setAccepting(TTSConfig.enabled));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            TTSManager.setAccepting(false));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TTSManager.shutdown());
        ChatListener.register();
        MCChatTTS.LOGGER.info("MC Chat TTS 클라이언트 로드됨");

        KeyMapping.Category ttsCategory = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("mc-chat-tts", "keycategory")
        );

        keySkip = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "TTS 스킵",
            InputConstants.KEY_END,
            ttsCategory
        ));
        keyStop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "TTS 전체 중단",
            InputConstants.KEY_HOME,
            ttsCategory
        ));

        keyConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.mc-chat-tts.config",
            InputConstants.KEY_F9,
            ttsCategory
        ));

        // 매 틱마다 키 입력 확인
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyConfig.consumeClick()) {
                if (client.gui.screen() == null && client.gui.overlay() == null) {
                    client.setScreenAndShow(TTSConfigScreen.create(null));
                }
            }
            TTSManager.setAccepting(client.player != null && TTSConfig.enabled);
            if (client.player == null || client.gui.screen() != null) {
                while (keySkip.consumeClick()) { }
                while (keyStop.consumeClick()) { }
                return;
            }
            
            while (keySkip.consumeClick()) {
                TTSManager.skip();
            }
            while (keyStop.consumeClick()) {
                TTSManager.clearAll();
            }
        });
    }
}