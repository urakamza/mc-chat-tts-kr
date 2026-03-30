package kr.urakamza.mcchattts;

import kr.urakamza.mcchattts.tts.TTSManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resources.Identifier;

public class MCChatTTSClient implements ClientModInitializer {

    public static KeyMapping keySkip;
    public static KeyMapping keyStop;

    @Override
    public void onInitializeClient() {
        TTSConfig.load();
        TTSManager.init();
        ChatListener.register();
        MCChatTTS.LOGGER.info("MC Chat TTS 클라이언트 로드됨");

        KeyMapping.Category ttsCategory = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("mc-chat-tts", "keycategory")
        );

        keySkip = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "TTS 스킵",
            GLFW.GLFW_KEY_END,
            ttsCategory
        ));
        keyStop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "TTS 전체 중단",
            GLFW.GLFW_KEY_HOME,
            ttsCategory
        ));

        // 매 틱마다 키 입력 확인
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            
            while (keySkip.consumeClick()) {
                TTSManager.skip();
            }
            while (keyStop.consumeClick()) {
                TTSManager.clearAll();
            }
        });
    }
}