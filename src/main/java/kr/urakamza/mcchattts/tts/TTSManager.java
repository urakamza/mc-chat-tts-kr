package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;
import kr.urakamza.mcchattts.TTSConfig;
import kr.urakamza.mcchattts.audio.AudioPlayer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public class TTSManager {
    private static final Object lock = new Object();
    private static final ArrayDeque<TTSItem> queue = new ArrayDeque<>();
    private static Thread workerThread;
    private static Job current;
    private static boolean running;
    private static boolean accepting = true;
    // A safety ceiling also applies when smart queue trimming is disabled.
    private static final int HARD_QUEUE_LIMIT = 1000;

    public record TTSItem(String text, String engine, int speed) {}
    private static final class Job {
        final TTSItem item;
        volatile boolean cancelled;
        Job(TTSItem item) { this.item = item; }
    }

    public static void init() {
        synchronized (lock) {
            if (workerThread != null && workerThread.isAlive()) return;
            running = true;
            workerThread = new Thread(TTSManager::runWorker, "TTS-Worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    private static void runWorker() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (running && queue.isEmpty()) {
                    try { lock.wait(); }
                    catch (InterruptedException ignored) { }
                }
                if (!running) return;
                Thread.interrupted();
                job = new Job(queue.removeFirst());
                current = job;
            }
            try {
                synthesizeAndPlay(job);
            } catch (InterruptedException ignored) {
                // Cancellation affects this job, not the lifetime of the worker.
            } catch (Exception e) {
                if (!job.cancelled) MCChatTTS.LOGGER.error("TTS 재생 오류: {}", e.getMessage());
            } finally {
                synchronized (lock) {
                    if (current == job) current = null;
                    Thread.interrupted();
                }
            }
        }
    }

    public static void enqueue(String text, String nick, int speed) {
        if (text == null || text.isBlank() || !TTSConfig.enabled) return;
        String engine = TTSConfig.engine;
        int finalSpeed = speed;
        String voice = nick == null ? null : TTSConfig.userVoices.get(nick);
        if (voice != null) {
            String[] parts = voice.split(",", 2);
            engine = normalizeEngine(parts[0].strip());
            if (parts.length > 1) {
                try { finalSpeed = Integer.parseInt(parts[1].strip()); }
                catch (NumberFormatException ignored) { }
            }
        }
        synchronized (lock) {
            if (!running || !accepting) return;
            if (TTSConfig.forceLatest) {
                queue.clear();
                cancelCurrent();
            }
            int limit = TTSConfig.smartQueue
                ? Math.max(1, Math.min(HARD_QUEUE_LIMIT, TTSConfig.maxQueue)) : HARD_QUEUE_LIMIT;
            while (queue.size() >= limit) queue.removeFirst();
            queue.addLast(new TTSItem(text, engine, Math.max(-50, Math.min(50, finalSpeed))));
            lock.notifyAll();
        }
    }

    // Called with lock held: cancellation cannot race the audio start below.
    private static void cancelCurrent() {
        if (current != null) {
            current.cancelled = true;
            workerThread.interrupt();
        }
        AudioPlayer.stop();
        SAPIEngine.stop();
    }

    public static void skip() {
        synchronized (lock) { cancelCurrent(); }
    }

    public static void clearAll() {
        synchronized (lock) {
            queue.clear();
            cancelCurrent();
        }
    }

    public static void setAccepting(boolean value) {
        synchronized (lock) {
            if (accepting == value) return;
            accepting = value;
            if (!value) {
                queue.clear();
                cancelCurrent();
            }
        }
    }

    public static void shutdown() {
        synchronized (lock) {
            running = false;
            accepting = false;
            queue.clear();
            cancelCurrent();
            lock.notifyAll();
        }
    }

    public static boolean isPlaying() {
        return AudioPlayer.isPlaying() || SAPIEngine.isPlaying();
    }

    public static int queueSize() {
        synchronized (lock) { return queue.size(); }
    }

    private static void synthesizeAndPlay(Job job) throws InterruptedException {
        if (job.cancelled) return;
        TTSItem item = job.item;
        if (item.engine().startsWith("Edge")) {
            byte[] data = EdgeTTSEngine.synthesize(item.text(), item.engine(), item.speed());
            if (data != null) playAndWait(job, data);
        } else if (item.engine().equals("Google")) {
            byte[] data = GoogleTTSEngine.synthesize(item.text());
            if (data != null) playAndWait(job, data);
        } else {
            SAPIEngine.speak(item.text(), item.engine(), item.speed(), () -> job.cancelled);
        }
    }

    private static void playAndWait(Job job, byte[] data) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        synchronized (lock) {
            if (job.cancelled || current != job || !running || !accepting) return;
            AudioPlayer.play(data, finished::countDown);
        }
        // No fixed 30-second cutoff: the next utterance waits for actual completion.
        finished.await();
    }

    private static String normalizeEngine(String name) {
        if (name == null) return TTSConfig.engine;
        String raw = name.strip().toLowerCase(Locale.ROOT).replace(" ", "");
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
                if (voice.toLowerCase(Locale.ROOT).replace(" ", "").contains(raw)) {
                    return voice;
                }
            }
        }
        return name; // 못 찾으면 원본 반환
    }
}