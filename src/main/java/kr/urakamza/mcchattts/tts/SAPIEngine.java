package kr.urakamza.mcchattts.tts;

import kr.urakamza.mcchattts.MCChatTTS;

import java.util.ArrayList;
import java.util.List;

public class SAPIEngine {

    private static Process currentProcess = null;
    private static final Object lock = new Object();

    public static void speak(String text, String voiceName, int speed) {
        // SAPI Rate: -10 ~ 10, 우리 speed: -50 ~ 50 → 변환
        int sapiRate = Math.max(-10, Math.min(10, speed / 5));

        // 텍스트에서 작은따옴표 이스케이프
        String safeText = text.replace("'", "''");
        String safeVoice = voiceName.replace("'", "''");

        String script = String.format(
            "Add-Type -AssemblyName System.Speech;" +
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
            "try { $s.SelectVoice('%s') } catch {};" +
            "$s.Rate = %d;" +
            "$s.Speak('%s');",
            safeVoice, sapiRate, safeText
        );

        try {
            synchronized (lock) {
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-WindowStyle", "Hidden",
                    "-NoProfile",
                    "-Command", script
                );
                pb.redirectErrorStream(true);
                currentProcess = pb.start();
                currentProcess.waitFor(); // 재생 완료까지 블로킹
                currentProcess = null;
            }
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("SAPI 재생 오류: {}", e.getMessage());
        }
    }

    public static void stop() {
        synchronized (lock) {
            if (currentProcess != null && currentProcess.isAlive()) {
                currentProcess.destroyForcibly();
                currentProcess = null;
            }
        }
    }

    /** 시스템에 설치된 SAPI 음성 목록 반환 */
    public static List<String> getVoices() {
        List<String> voices = new ArrayList<>();
        String script =
            "Add-Type -AssemblyName System.Speech;" +
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
            "$s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }";

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-WindowStyle", "Hidden",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command", script
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                
                boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    MCChatTTS.LOGGER.warn("SAPI 음성 목록 타임아웃 (시도 {})", attempt + 1);
                    continue;
                }
                
                String output = new String(p.getInputStream().readAllBytes());
                for (String line : output.split("\\r?\\n")) {
                    String name = line.trim();
                    if (!name.isEmpty()) voices.add(name);
                }
                
                if (!voices.isEmpty()) break; // 성공 시 종료
                
            } catch (Exception e) {
                MCChatTTS.LOGGER.error("SAPI 음성 목록 오류 (시도 {}): {}", attempt + 1, e.getMessage());
            }
        }
        return voices;
    }
}