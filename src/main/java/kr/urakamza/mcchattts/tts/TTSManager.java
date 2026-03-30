package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;
import kr.urakamza.mcchattts.TTSConfig;
import kr.urakamza.mcchattts.audio.AudioPlayer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TTSManager {

    private static final BlockingQueue<TTSItem> queue = new LinkedBlockingQueue<>();
    private static Thread workerThread;

    public record TTSItem(String text, String engine, int speed) {}

    public static void init() {
        workerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TTSItem item = queue.take(); // 블로킹 대기
                    synthesizeAndPlay(item);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "TTS-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
        MCChatTTS.LOGGER.info("TTS 워커 시작됨");
    }

    public static void enqueue(String text, String nick, int speed) {
        String engine = TTSConfig.engine;
        int finalSpeed = speed;

        if (nick != null && TTSConfig.userVoices.containsKey(nick.toLowerCase())) {
            String val = TTSConfig.userVoices.get(nick.toLowerCase());
            String[] parts = val.split(",", 2);
            engine = normalizeEngine(parts[0].strip());
            if (parts.length > 1) {
                try { finalSpeed = Integer.parseInt(parts[1].strip()); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (TTSConfig.smartQueue) {
            int limit = Math.max(1, TTSConfig.maxQueue);
            while (queue.size() >= limit) queue.poll();
        }
        if (TTSConfig.forceLatest) {
            queue.clear();
            AudioPlayer.stop();
        }
        queue.offer(new TTSItem(text, engine, finalSpeed));
    }

    public static void skip() {
        AudioPlayer.stop();
        SAPIEngine.stop();
    }

    public static void clearAll() {
        queue.clear();
        AudioPlayer.stop();
        SAPIEngine.stop();
    }

    public static boolean isPlaying() {
        return AudioPlayer.isPlaying();
    }

    public static int queueSize() {
        return queue.size();
    }

   private static void synthesizeAndPlay(TTSItem item) {
        try {
            if (item.engine().startsWith("Edge")) {
                byte[] data = EdgeTTSEngine.synthesize(item.text(), item.engine(), item.speed());
                if (data != null) playAndWait(data);

            } else if (item.engine().equals("Google")) {
                byte[] data = GoogleTTSEngine.synthesize(item.text());
                if (data != null) playAndWait(data);

            } else {
                // SAPI — 직접 재생 (블로킹)
                SAPIEngine.speak(item.text(), item.engine(), item.speed());
            }
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("TTS 재생 오류: {}", e.getMessage());
        }
    }

    private static void playAndWait(byte[] data) throws InterruptedException {
        Object lock = new Object();
        synchronized (lock) {
            AudioPlayer.play(data, () -> {
                synchronized (lock) { lock.notifyAll(); }
            });
            lock.wait(30000);
        }
    }

    private static String normalizeEngine(String name) {
        if (name == null) return TTSConfig.engine;
        String raw = name.strip().toLowerCase().replace(" ", "");
        if (raw.length() < 2) return TTSConfig.engine;

        // 정확 일치
        if (raw.equals("google")) return "Google";
        if (raw.equals("edge남")) return "Edge 남";
        if (raw.equals("edge여")) return "Edge 여";

        // 별칭
        if (raw.equals("ggl") || raw.equals("goo") || raw.equals("gle")) return "Google";
        if (raw.equals("egm") || raw.equals("edgem")) return "Edge 남";
        if (raw.equals("egf") || raw.equals("edgef")) return "Edge 여";

        // SAPI 부분 일치 (앞 3글자 이상)
        if (raw.length() >= 3) {
            for (String voice : TTSConfig.sapiVoices) {
                if (voice.toLowerCase().replace(" ", "").contains(raw)) {
                    return voice;
                }
            }
        }
        return name; // 못 찾으면 원본 반환
    }
}