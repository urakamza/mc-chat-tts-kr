package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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


    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public static byte[] synthesize(String text, String engineName, int speed) {
        if (Thread.currentThread().isInterrupted()) return null;
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


        String config =
            "X-Timestamp:" + ts + "\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{" +
            "\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\"," +
            "\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
        String request =
            "X-RequestId:" + reqId + "\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:" + ts + "\r\n" +
            "Path:ssml\r\n\r\n" + ssml;

        CompletableFuture<byte[]> audio = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<WebSocket> socket = new AtomicReference<>();
        CompletableFuture<WebSocket> connecting = null;
        try {
            connecting = CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR +
                    ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR + ".0.0.0")
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder textParts = new StringBuilder();
                    private final ByteArrayOutputStream binaryParts = new ByteArrayOutputStream();
                    private final ByteArrayOutputStream audioBytes = new ByteArrayOutputStream();

                    @Override
                    public void onOpen(WebSocket ws) {
                        socket.set(ws);
                        if (finished.get()) ws.abort();
                        else ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        if (finished.get() || audio.isDone()) return null;
                        textParts.append(data);
                        if (last) {
                            String message = textParts.toString();
                            textParts.setLength(0);
                            if (message.contains("Path:turn.end")) audio.complete(audioBytes.toByteArray());
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                        if (finished.get() || audio.isDone()) return null;
                        byte[] fragment = new byte[data.remaining()];
                        data.get(fragment);
                        binaryParts.writeBytes(fragment);
                        if (last) {
                            // JDK callbacks can split one Edge message into several fragments.
                            byte[] message = binaryParts.toByteArray();
                            binaryParts.reset();
                            if (message.length < 2) {
                                audio.completeExceptionally(new IOException("Edge TTS binary header is missing"));
                            } else {
                                int headerLength = ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
                                int start = 2 + headerLength;
                                if (start > message.length) {
                                    audio.completeExceptionally(new IOException("Edge TTS binary header is incomplete"));
                                } else {
                                    String header = new String(message, 2, headerLength, StandardCharsets.UTF_8);
                                    if (header.contains("Path:audio"))
                                        audioBytes.write(message, start, message.length - start);
                                }
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        audio.completeExceptionally(new IOException("Edge TTS closed before completion: " + statusCode));
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        audio.completeExceptionally(error);
                    }
                });

            // JDK WebSocket permits only one outstanding text send at a time.
            connecting.thenCompose(ws -> {
                if (finished.get()) {
                    ws.abort();
                    return CompletableFuture.completedFuture(ws);
                }
                return ws.sendText(config, true);
            }).thenCompose(ws -> {
                if (finished.get()) return CompletableFuture.completedFuture(ws);
                return ws.sendText(request, true);
            }).whenComplete((ws, error) -> {
                if (error != null) audio.completeExceptionally(error);
            });

            byte[] result = audio.get(15, TimeUnit.SECONDS);
            return result.length == 0 ? null : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            MCChatTTS.LOGGER.warn("Edge TTS 타임아웃");
            return null;
        } catch (ExecutionException e) {
            MCChatTTS.LOGGER.error("Edge TTS 실패: {}", e.getCause().toString());
            return null;
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("Edge TTS 실패: {}", e.getMessage());
            return null;
        } finally {
            finished.set(true);
            if (connecting != null && !connecting.isDone()) connecting.cancel(true);
            WebSocket ws = socket.get();
            if (ws != null) ws.abort();
        }
    }

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