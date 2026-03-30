package kr.urakamza.mcchattts.audio;

import kr.urakamza.mcchattts.MCChatTTS;
import kr.urakamza.mcchattts.TTSConfig;
import javazoom.jl.decoder.*;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioPlayer {

    private static SourceDataLine currentLine;
    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static volatile boolean stopRequested = false;

    public static boolean isPlaying() {
        return isPlaying.get();
    }

    public static void play(byte[] mp3Data, Runnable onFinishCallback) {
        stop();
        stopRequested = false;

        Thread t = new Thread(() -> {
            try {
                isPlaying.set(true);
                Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3Data));
                Decoder decoder = new Decoder();

                AudioFormat format = null;
                SourceDataLine line = null;

                Header header;
                while (!stopRequested && (header = bitstream.readFrame()) != null) {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    if (line == null) {
                        int sampleRate = output.getSampleFrequency();
                        int channels = output.getChannelCount();
                        format = new AudioFormat(sampleRate, 16, channels, true, false);
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                        line = (SourceDataLine) AudioSystem.getLine(info);
                        line.open(format);
                        // 볼륨 설정
                        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                            FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                            float dB = (float)(Math.log10(Math.max(TTSConfig.volume, 0.0001f)) * 20);
                            gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB)));
                        }
                        currentLine = line;
                        line.start();
                    }

                    short[] pcm = output.getBuffer();
                    int len = output.getBufferLength();
                    byte[] bytes = new byte[len * 2];
                    for (int i = 0; i < len; i++) {
                        bytes[i * 2] = (byte)(pcm[i] & 0xFF);
                        bytes[i * 2 + 1] = (byte)((pcm[i] >> 8) & 0xFF);
                    }
                    line.write(bytes, 0, bytes.length);
                    bitstream.closeFrame();
                }

                if (line != null) {
                    line.drain();
                    line.close();
                }
                bitstream.close();

            } catch (Exception e) {
                MCChatTTS.LOGGER.error("오디오 재생 오류: {}", e.getMessage());
            } finally {
                isPlaying.set(false);
                if (onFinishCallback != null) onFinishCallback.run();
            }
        }, "TTS-Audio");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        stopRequested = true;
        if (currentLine != null) {
            try { currentLine.stop(); currentLine.close(); } catch (Exception ignored) {}
            currentLine = null;
        }
        isPlaying.set(false);
    }
}