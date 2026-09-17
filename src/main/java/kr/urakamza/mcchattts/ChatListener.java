package kr.urakamza.mcchattts;

import kr.urakamza.mcchattts.text.TextProcessor;
import kr.urakamza.mcchattts.tts.TTSManager;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatListener {

    private static final Pattern CHAT_PATTERN = Pattern.compile("<([^>]+)>(.+)");

    private static String lastNick = null;
    private static long lastNickTime = 0;

    public static void register() {
        // 일반 채팅
        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                if(!TTSConfig.enabled) return;
                // The profile identifies the player independently of the displayed chat format.
                if (sender != null) {
                    String body = signedMessage != null
                        ? signedMessage.decoratedContent().getString() : message.getString();
                    handleChat(sender.name(), body);
                    return;
                }
                // Keep compatibility with unsigned messages without a sender profile.
                String raw = message.getString();
                if (!handleLegacyChat(raw)) {
                    handleChat(null, signedMessage != null
                        ? signedMessage.decoratedContent().getString() : raw);
                }
            });

        // 시스템 메시지
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if(!TTSConfig.enabled) return;
            if (overlay) return;
            String msg = message.getString();

            // <닉네임> 형식이면 일반 채팅으로 처리
            if (handleLegacyChat(msg)) return;

            // 나머지는 시스템 메시지
            if (!TTSConfig.readSystem) return;
            msg = TextProcessor.processSystemMessage(TextProcessor.applyWordFilter(msg));
            if (msg.isBlank()) return;
            TTSManager.enqueue(msg, null, TTSConfig.speed);
        });
    }

    private static boolean handleLegacyChat(String message) {
        Matcher match = CHAT_PATTERN.matcher(message);
        if (!match.find()) return false;
        String nick = match.group(1).replaceAll("\\[.*?]", "").strip();
        handleChat(nick, match.group(2).strip());
        return true;
    }

    private static void handleChat(String nick, String chat) {
        if (!TTSConfig.enabled || chat.isBlank()) return;
        // Case-sensitive identity comparisons happen before any pronunciation transforms.
        if (nick != null && TTSConfig.nicknameFilter.stream().anyMatch(nick::equals)) return;
        chat = TextProcessor.applyWordFilter(chat);

        // 이름 읽기
        String finalText;
        long now = System.currentTimeMillis();

        if (TTSConfig.readNick && nick != null && !nick.isBlank()) {
            if (TTSConfig.skipRepeatNick) {
                long timeout = TTSConfig.nickTimeout * 1000L;
                boolean sameUser = nick.equals(lastNick);
                boolean withinTime = (now - lastNickTime) <= timeout;
                finalText = (sameUser && withinTime) ? chat : nick + ", " + chat;
            } else {
                finalText = nick + ", " + chat;
            }
        } else {
            finalText = chat;
        }

        lastNick = nick;
        lastNickTime = now;

        // 텍스트 후처리
        finalText = TextProcessor.process(finalText);
        if (finalText.isBlank()) return;

        TTSManager.enqueue(finalText, nick, TTSConfig.speed);
    }
}