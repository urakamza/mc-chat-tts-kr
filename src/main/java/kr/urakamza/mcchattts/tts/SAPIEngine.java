package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;
import kr.urakamza.mcchattts.TTSConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class SAPIEngine {
    private static Process currentProcess;
    private static final Object lock = new Object();
    private static final AtomicBoolean unsupportedLogged = new AtomicBoolean();
    private static final String VOICE_PREFIX = "MC_CHAT_TTS_VOICE:";

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static void speak(String text, String voiceName, int speed) {
        speak(text, voiceName, speed, () -> false);
    }

    public static void speak(String text, String voiceName, int speed, BooleanSupplier cancelled) {
        if (!isSupported()) {
            if (unsupportedLogged.compareAndSet(false, true))
                MCChatTTS.LOGGER.warn("SAPI는 Windows에서만 지원됩니다. Google 또는 Edge 엔진을 선택하세요.");
            return;
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        int rate = Math.max(-10, Math.min(10, speed / 5));
        int volume = Math.max(0, Math.min(100, Math.round(TTSConfig.volume * 100)));
        String script =
            "$ErrorActionPreference='Stop';" +
            "Add-Type -AssemblyName System.Speech;" +
            "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
            "try {" +
            "$s.SelectVoice(" + psString(voiceName) + ");" +
            "$s.Rate=" + rate + ";" +
            "$s.Volume=" + volume + ";" +
            "$s.Speak(" + psString(text) + ");" +
            "} finally {$s.Dispose()}";

        Process process = null;
        try {
            ProcessBuilder builder = powershell(script);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            synchronized (lock) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                process = builder.start();
                currentProcess = process;
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 && !cancelled.getAsBoolean())
                MCChatTTS.LOGGER.warn("SAPI 재생 실패 (종료 코드 {}): 설치된 음성과 음성 이름을 확인하세요.", exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!cancelled.getAsBoolean()) MCChatTTS.LOGGER.error("SAPI 재생 오류: {}", e.getMessage());
        } finally {
            synchronized (lock) {
                if (process != null && process.isAlive()) process.destroyForcibly();
                if (currentProcess == process) currentProcess = null;
            }
        }
    }

    public static boolean isPlaying() {
        synchronized (lock) { return currentProcess != null && currentProcess.isAlive(); }
    }

    public static void stop() {
        synchronized (lock) {
            if (currentProcess != null) {
                if (currentProcess.isAlive()) currentProcess.destroyForcibly();
                currentProcess = null;
            }
        }
    }

    /** Discover usable voices without mixing PowerShell diagnostics into the list. */
    public static List<String> getVoices() {
        if (!isSupported()) return List.of();
        String script =
            "$ErrorActionPreference='Stop';" +
            "Add-Type -AssemblyName System.Speech;" +
            "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
            "try {" +
            "$s.GetInstalledVoices() | Where-Object {$_.Enabled} | ForEach-Object {" +
            "$name=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.VoiceInfo.Name));" +
            "[Console]::WriteLine('" + VOICE_PREFIX + "'+$name)" +
            "}" +
            "} finally {$s.Dispose()}";

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            Process process = null;
            Path output = null;
            try {
                // A file avoids filling a pipe while waitFor waits for process exit.
                output = Files.createTempFile("mc-chat-tts-voices-", ".txt");
                process = powershell(script).redirectOutput(output.toFile()).start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    MCChatTTS.LOGGER.warn("SAPI 음성 목록 타임아웃 (시도 {})", attempt);
                    continue;
                }
                if (process.exitValue() != 0) {
                    MCChatTTS.LOGGER.warn("SAPI 음성 목록 조회 실패 (시도 {}, 종료 코드 {})", attempt, process.exitValue());
                    continue;
                }
                LinkedHashSet<String> voices = new LinkedHashSet<>();
                for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                    if (!line.startsWith(VOICE_PREFIX)) continue;
                    String name = new String(Base64.getDecoder().decode(line.substring(VOICE_PREFIX.length()).strip()),
                        StandardCharsets.UTF_8).strip();
                    if (!name.isEmpty()) voices.add(name);
                }
                // A successful empty result means no usable voices are installed.
                return new ArrayList<>(voices);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                MCChatTTS.LOGGER.warn("SAPI 음성 목록 오류 (시도 {}): {}", attempt, e.getMessage());
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try { process.waitFor(1, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (output != null) {
                    try { Files.deleteIfExists(output); }
                    catch (Exception ignored) { output.toFile().deleteOnExit(); }
                }
            }
            if (Thread.currentThread().isInterrupted()) return List.of();
        }
        return List.of();
    }

    private static ProcessBuilder powershell(String script) {
        // Windows PowerShell's EncodedCommand uses UTF-16LE.
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile",
            "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    private static String psString(String value) {
        // Keep chat text and voice names out of the PowerShell source syntax.
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + encoded + "'))";
    }
}
