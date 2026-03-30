package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class GoogleTTSEngine {

    public static byte[] synthesize(String text) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://translate.google.com/translate_tts?ie=UTF-8" +
                         "&q=" + encoded + "&tl=ko&client=tw-ob";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

            HttpResponse<byte[]> response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                MCChatTTS.LOGGER.error("Google TTS HTTP 오류: {}", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("Google TTS 실패: {}", e.getMessage());
            return null;
        }
    }
}