package kr.urakamza.mcchattts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class TTSConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("mc-chat-tts.json");

    // ===== 설정값 =====
    public static String engine = "Google";       // Google / Edge 남 / Edge 여
    public static int speed = 0;                   // -50 ~ 50
    public static float volume = 1.0f;
    public static boolean readNick = true;
    public static boolean readSystem = true;
    public static boolean forceLower = true;
    public static boolean removeCoord = true;
    public static boolean useJamo = true;
    public static boolean partialRead = false;     // 반복 문자 줄이기
    public static boolean skipRepeatNick = false;  // 연속 채팅 이름 생략
    public static int nickTimeout = 10;
    public static boolean trimLong = false;        // 긴 문장 자르기
    public static int trimLength = 80;
    public static boolean smartQueue = false;      // 긴 대기열 자동 삭제
    public static int maxQueue = 10;
    public static boolean forceLatest = false;     // 새 채팅 우선모드
    public static List<String> nicknameFilter = new ArrayList<>();
    public static List<String> wordFilter = new ArrayList<>();
    public static List<String> sapiVoices = new ArrayList<>();
    public static Map<String, String> userVoices = new HashMap<>();
    

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData data = GSON.fromJson(r, ConfigData.class);
            if (data == null) return;
            engine         = orDefault(data.engine, engine);
            speed          = data.speed;
            volume         = data.volume;
            readNick       = data.readNick;
            readSystem     = data.readSystem;
            forceLower     = data.forceLower;
            removeCoord    = data.removeCoord;
            useJamo        = data.useJamo;
            partialRead    = data.partialRead;
            skipRepeatNick = data.skipRepeatNick;
            nickTimeout    = data.nickTimeout;
            trimLong       = data.trimLong;
            trimLength     = data.trimLength;
            smartQueue     = data.smartQueue;
            maxQueue       = data.maxQueue;
            forceLatest    = data.forceLatest;
            sapiVoices = new ArrayList<>();
            Thread sapiThread = new Thread(() -> {
                MCChatTTS.LOGGER.info("SAPI 음성 목록 로드 시작...");
                List<String> voices = kr.urakamza.mcchattts.tts.SAPIEngine.getVoices();
                MCChatTTS.LOGGER.info("SAPI 음성 목록 결과: {}개 - {}", voices.size(), voices);
                if (!voices.isEmpty()) sapiVoices = voices;
            }, "SAPI-Voice-Loader");
            sapiThread.setDaemon(true);
            sapiThread.start();
            if (data.nicknameFilter != null) nicknameFilter = data.nicknameFilter;
            if (data.wordFilter != null)     wordFilter     = data.wordFilter;
            if (data.userVoices != null) userVoices = data.userVoices;
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("설정 로드 실패: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            ConfigData data      = new ConfigData();
            data.engine          = engine;
            data.speed           = speed;
            data.volume          = volume;
            data.readNick        = readNick;
            data.readSystem      = readSystem;
            data.forceLower      = forceLower;
            data.removeCoord     = removeCoord;
            data.useJamo         = useJamo;
            data.partialRead     = partialRead;
            data.skipRepeatNick  = skipRepeatNick;
            data.nickTimeout     = nickTimeout;
            data.trimLong        = trimLong;
            data.trimLength      = trimLength;
            data.smartQueue      = smartQueue;
            data.maxQueue        = maxQueue;
            data.forceLatest     = forceLatest;
            data.nicknameFilter  = nicknameFilter;
            data.wordFilter      = wordFilter;
            data.userVoices = userVoices;
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (Exception e) {
            MCChatTTS.LOGGER.error("설정 저장 실패: {}", e.getMessage());
        }
    }

    private static String orDefault(String val, String def) {
        return (val != null && !val.isEmpty()) ? val : def;
    }

    // JSON 직렬화용 내부 클래스
    private static class ConfigData {
        String engine = "Google";
        int speed = 0;
        float volume = 1.0f;
        boolean readNick = true, readSystem = true, forceLower = true;
        boolean removeCoord = true, useJamo = true, partialRead = false;
        boolean skipRepeatNick = false, trimLong = false;
        boolean smartQueue = false, forceLatest = false;
        int nickTimeout = 10, trimLength = 80, maxQueue = 10;
        List<String> nicknameFilter = new ArrayList<>();
        List<String> wordFilter = new ArrayList<>();
        Map<String, String> userVoices = new HashMap<>();
    }
}