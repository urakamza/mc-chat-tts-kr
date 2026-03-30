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
                String raw = message.getString();
                Matcher m = CHAT_PATTERN.matcher(raw);
                if (m.find()) {
                    String nick = m.group(1).replaceAll("\\[.*?]", "").strip();
                    String chat = m.group(2).strip();
                    handleChat(nick, chat);
                }
            });

        // 시스템 메시지
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String msg = message.getString();

            // <닉네임> 형식이면 일반 채팅으로 처리
            Matcher m = CHAT_PATTERN.matcher(msg);
            if (m.find()) {
                String nick = m.group(1).replaceAll("\\[.*?]", "").strip();
                String chat = m.group(2).strip();
                handleChat(nick, chat);
                return;
            }

            // 나머지는 시스템 메시지
            if (!TTSConfig.readSystem) return;
            msg = TextProcessor.process(msg);
            if (msg.isBlank()) return;
            TTSManager.enqueue(msg, null, TTSConfig.speed);
        });
    }

    private static void handleChat(String nick, String chat) {
        // 닉네임 필터
        if (!TTSConfig.nicknameFilter.isEmpty()) {
            String nickLower = nick.toLowerCase();
            boolean blocked = TTSConfig.nicknameFilter.stream()
                .anyMatch(f -> f.toLowerCase().equals(nickLower));
            if (blocked) return;
        }

        // 단어 필터
        chat = TextProcessor.applyWordFilter(chat);

        // 로그용 원본 보존
        // String logNick = nick;
        // String logChat = chat;

        // 소문자 변환
        if (TTSConfig.forceLower) {
            nick = nick.toLowerCase();
            chat = chat.toLowerCase();
        }

        // 이름 읽기
        String finalText;
        long now = System.currentTimeMillis();

        if (TTSConfig.readNick) {
            if (TTSConfig.skipRepeatNick) {
                long timeout = TTSConfig.nickTimeout * 1000L;
                boolean sameUser = nick.equalsIgnoreCase(lastNick);
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