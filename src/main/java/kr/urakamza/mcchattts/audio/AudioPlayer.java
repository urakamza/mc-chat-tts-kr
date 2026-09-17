package kr.urakamza.mcchattts.audio;

import kr.urakamza.mcchattts.MCChatTTS;
import kr.urakamza.mcchattts.TTSConfig;
import javazoom.jl.decoder.*;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;

public class AudioPlayer {
    private static Session current;

    private static final class Session {
        volatile boolean cancelled;
        SourceDataLine line;
        final float volume = Math.max(0, Math.min(1, TTSConfig.volume));

        synchronized void cancel() {
            cancelled = true;
            closeLine();
        }

        synchronized void closeLine() {
            if (line == null) return;
            try { line.stop(); } catch (Exception ignored) { }
            try { line.flush(); } catch (Exception ignored) { }
            try { line.close(); } catch (Exception ignored) { }
            line = null;
        }
    }

    public static synchronized boolean isPlaying() {
        return current != null && !current.cancelled;
    }

    public static synchronized void play(byte[] mp3Data, Runnable onFinishCallback) {
        stop();
        Session session = new Session();
        current = session;
        Thread thread = new Thread(() -> decode(session, mp3Data, onFinishCallback), "TTS-Audio");
        thread.setDaemon(true);
        thread.start();
    }

    private static void decode(Session session, byte[] data, Runnable onFinish) {
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(data));
        SourceDataLine line = null;
        try {
            Decoder decoder = new Decoder();
            Header header;
            while (!session.cancelled && (header = bitstream.readFrame()) != null) {
                try {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (line == null) {
                        AudioFormat format = new AudioFormat(output.getSampleFrequency(), 16,
                            output.getChannelCount(), true, false);
                        line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                        line.open(format);
                        synchronized (session) {
                            if (session.cancelled) break;
                            session.line = line;
                            line.start();
                        }
                    }
                    short[] pcm = output.getBuffer();
                    byte[] bytes = new byte[output.getBufferLength() * 2];
                    for (int i = 0; i < output.getBufferLength(); i++) {
                        // Software volume also supports devices without MASTER_GAIN, including mute.
                        short sample = (short) (pcm[i] * session.volume);
                        bytes[i * 2] = (byte) sample;
                        bytes[i * 2 + 1] = (byte) (sample >> 8);
                    }
                    int offset = 0;
                    while (!session.cancelled && offset < bytes.length) {
                        int written = line.write(bytes, offset, bytes.length - offset);
                        if (written <= 0) break;
                        offset += written;
                    }
                } finally {
                    bitstream.closeFrame();
                }
            }
            if (!session.cancelled && line != null) line.drain();
        } catch (Exception e) {
            if (!session.cancelled) MCChatTTS.LOGGER.error("오디오 재생 오류: {}", e.getMessage());
        } finally {
            session.closeLine();
            // Covers cancellation or an exception between line.open and publication.
            if (line != null) {
                try { line.close(); } catch (Exception ignored) { }
            }
            try { bitstream.close(); } catch (Exception ignored) { }
            synchronized (AudioPlayer.class) {
                if (current == session) current = null;
            }
            if (onFinish != null) onFinish.run();
        }
    }

    public static synchronized void stop() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }
}
