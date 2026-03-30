package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;
import okhttp3.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EdgeTTSEngine {

    private static final String VOICE_MALE        = "ko-KR-InJoonNeural";
    private static final String VOICE_FEMALE      = "ko-KR-SunHiNeural";
    private static final String TOKEN             = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_VERSION  = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR    = "143";
    private static final String SEC_MS_GEC_VER    = "1-" + CHROMIUM_VERSION;
    private static final String WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/" +
        "readaloud/edge/v1?TrustedClientToken=" + TOKEN;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    public static byte[] synthesize(String text, String engineName, int speed) {
        String voice    = engineName.contains("남") ? VOICE_MALE : VOICE_FEMALE;
        String speedStr = (speed >= 0 ? "+" : "") + speed + "%";
        String connId   = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String reqId    = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String ts       = ZonedDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.ENGLISH));

        String ssml =
            "<speak version='1.0' " +
            "xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ko-KR'>" +
            "<voice name='" + voice + "'>" +
            "<prosody rate='" + speedStr + "'>" + escapeXml(text) + "</prosody>" +
            "</voice></speak>";

        String secMsGec = generateSecMsGec();

        String url = WSS_BASE
            + "&ConnectionId=" + connId
            + "&Sec-MS-GEC=" + secMsGec
            + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VER;

        Request request = new Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/" + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36 " +
                "Edg/" + CHROMIUM_MAJOR + ".0.0.0")
            .build();

        ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] success = {false};

        WebSocket ws = CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                // 1) speech.config
                String cfg =
                    "X-Timestamp:" + ts + "\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{" +
                    "\"metadataoptions\":{" +
                    "\"sentenceBoundaryEnabled\":\"false\"," +
                    "\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
                ws.send(cfg);

                // 2) ssml
                String ssmlMsg =
                    "X-RequestId:" + reqId + "\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:" + ts + "\r\n" +
                    "Path:ssml\r\n\r\n" + ssml;
                ws.send(ssmlMsg);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("Path:turn.end")) {
                    success[0] = true;
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket ws, okio.ByteString bytes) {
                byte[] data = bytes.toByteArray();
                
                if (data.length < 2) return;
                int headerLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                
                if (data.length < 2 + headerLen) return;
                
                // 헤더 텍스트 파싱
                String header = new String(data, 2, headerLen, StandardCharsets.UTF_8);
                
                // 헤더에 Path:audio 가 있으면 나머지가 오디오 데이터
                if (header.contains("Path:audio")) {
                    int audioStart = 2 + headerLen;
                    if (audioStart < data.length) {
                        audioOut.write(data, audioStart, data.length - audioStart);
                    }
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                MCChatTTS.LOGGER.error("Edge TTS 연결 실패: {} (HTTP {})",
                    t.getMessage(),
                    response != null ? response.code() : "N/A");
                latch.countDown();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(15, TimeUnit.SECONDS);
            ws.close(1000, null);

            if (!ok || !success[0]) {
                MCChatTTS.LOGGER.warn("Edge TTS 실패 또는 타임아웃");
                return null;
            }

            byte[] result = audioOut.toByteArray();
            return result.length > 0 ? result : null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // private static int indexOf(byte[] data, byte[] pattern) {
    //     outer:
    //     for (int i = 0; i <= data.length - pattern.length; i++) {
    //         for (int j = 0; j < pattern.length; j++) {
    //             if (data[i + j] != pattern[j]) continue outer;
    //         }
    //         return i;
    //     }
    //     return -1;
    // }

    private static String generateSecMsGec() {
        try {
            // 1. 현재 Unix 타임스탬프 (초 단위, 소수점 포함)
            double ticks = System.currentTimeMillis() / 1000.0;

            // 2. Windows file time epoch으로 변환 (1601-01-01 기준)
            ticks += 11644473600L;

            // 3. 5분(300초) 단위로 버림
            ticks -= ticks % 300;

            // 4. 100나노초 단위로 변환 (1e9 / 100 = 1e7)
            long ticksLong = (long)(ticks * 1e7);

            // 5. SHA256 해시
            String strToHash = ticksLong + TOKEN;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(strToHash.getBytes(StandardCharsets.US_ASCII));

            // 6. 대문자 hex
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();

        } catch (Exception e) {
            MCChatTTS.LOGGER.error("Sec-MS-GEC 생성 실패: {}", e.getMessage());
            return "";
        }
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("'", "&apos;")
                   .replace("\"", "&quot;");
    }
}