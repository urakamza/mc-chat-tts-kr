package kr.urakamza.mcchattts;

import me.shedaniel.clothconfig2.api.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.*;
import java.util.stream.Collectors;

@Environment(EnvType.CLIENT)
public class TTSConfigScreen {
    static class ButtonEntry extends AbstractConfigListEntry<Void> {
        private final Button button;

        public ButtonEntry(Component label, Button.OnPress onPress) {
            super(label, false);
            this.button = Button.builder(label, onPress).build();
        }

        @Override
        public Void getValue() { return null; }

        @Override
        public Optional<Void> getDefaultValue() { return Optional.empty(); }

        @Override
        public void save() {}

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return List.of(button);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean isHovered, float delta) {
            button.setX(x + entryWidth / 2 - 75);
            button.setY(y);
            button.setWidth(150);
            button.setHeight(20);
            button.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }
    static class MultiLineListEntry extends AbstractConfigListEntry<List<String>> {
        private final net.minecraft.client.gui.components.MultiLineEditBox textBox;
        private final List<String> defaultValue;
        private java.util.function.Consumer<List<String>> saveConsumer;
        private static final int HEIGHT = 60;

        private final String initialValue; // 초기값 저장

        public MultiLineListEntry(Component label, List<String> value, List<String> defaultValue) {
            super(label, false);
            this.defaultValue = defaultValue;
            this.initialValue = String.join("\n", value); // 초기값 기억
            this.textBox = new net.minecraft.client.gui.components.MultiLineEditBox.Builder()
                .setX(0).setY(0)
                .setPlaceholder(Component.literal("한 줄에 하나씩..."))
                .build(Minecraft.getInstance().font, 200, HEIGHT, label);
            this.textBox.setValue(initialValue);
        }

        public MultiLineListEntry setSaveConsumer(java.util.function.Consumer<List<String>> consumer) {
            this.saveConsumer = consumer;
            return this;
        }

        @Override
        public List<String> getValue() {
            return Arrays.stream(textBox.getValue().split("\n"))
                .map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        }

        @Override
        public Optional<List<String>> getDefaultValue() {
            return Optional.of(defaultValue);
        }

        @Override
        public void save() {
            if (saveConsumer != null) saveConsumer.accept(getValue());
        }

        @Override
        public int getItemHeight() { return HEIGHT + 4; }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            return List.of(textBox);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            return List.of(textBox);
        }

        @Override
        public boolean isEdited() {
            return !textBox.getValue().equals(initialValue);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean isHovered, float delta) {
            textBox.setX(x);
            textBox.setY(y);
            try {
                var wf = textBox.getClass().getSuperclass().getDeclaredField("width");
                wf.setAccessible(true);
                wf.set(textBox, entryWidth);
            } catch (Exception ignored) {}
            textBox.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }
    public static Screen create(Screen parent) {
        
        try {
            List<String> engines = new ArrayList<>(Arrays.asList("Google", "Edge 남", "Edge 여"));
            if (TTSConfig.sapiVoices != null) engines.addAll(TTSConfig.sapiVoices);

            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("MC Chat TTS 설정"))
                .setSavingRunnable(TTSConfig::save);

            ConfigEntryBuilder entry = builder.entryBuilder();

            // ===== 기본 설정 =====
            ConfigCategory basic = builder.getOrCreateCategory(Component.literal("기본 설정"));
            basic.addEntry(entry.startStringDropdownMenu(Component.literal("TTS 엔진"), TTSConfig.engine).setSelections(engines).setDefaultValue("Edge 여").setSuggestionMode(false).setSaveConsumer(v -> TTSConfig.engine = v).build());
            basic.addEntry(entry.startIntSlider(Component.literal("속도 (Google 엔진은 적용 안됨)"), TTSConfig.speed, -50, 50).setDefaultValue(0).setSaveConsumer(v -> TTSConfig.speed = v).build());
            basic.addEntry(entry.startIntSlider(Component.literal("볼륨"), (int)(TTSConfig.volume * 100), 0, 100).setDefaultValue(100).setTextGetter(v -> Component.literal(v + "%")).setSaveConsumer(v -> TTSConfig.volume = v / 100.0f).build());
            // 키 바인딩 안내
            basic.addEntry(entry.startTextDescription(Component.literal("§7── 키 바인딩 ──")).build());
            basic.addEntry(entry.startTextDescription(Component.literal("§e스킵: §f" + MCChatTTSClient.keySkip.getTranslatedKeyMessage().getString() + "  §e전체 중단: §f" + MCChatTTSClient.keyStop.getTranslatedKeyMessage().getString())).build());
            basic.addEntry(new ButtonEntry(
                Component.literal("키 설정 열기"),
                btn -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(
                        new Screen(Component.literal("")) {
                            @Override
                            protected void init() { mc.setScreen(TTSConfigScreen.create(parent)); }
                            @Override
                            public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {}
                        },
                        mc.options
                    ));
                }
            ));
            // ===== 읽기 옵션 =====
            ConfigCategory readOpts = builder.getOrCreateCategory(Component.literal("읽기 옵션"));
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("이름 읽기"), TTSConfig.readNick).setDefaultValue(true).setSaveConsumer(v -> TTSConfig.readNick = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("시스템 메시지 읽기"), TTSConfig.readSystem).setDefaultValue(true).setSaveConsumer(v -> TTSConfig.readSystem = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("소문자로 강제 변환"), TTSConfig.forceLower).setDefaultValue(true).setSaveConsumer(v -> TTSConfig.forceLower = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("좌표 소수점 제거"), TTSConfig.removeCoord).setDefaultValue(true).setSaveConsumer(v -> TTSConfig.removeCoord = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("자모음 읽기 보정"), TTSConfig.useJamo).setDefaultValue(true).setSaveConsumer(v -> TTSConfig.useJamo = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("반복 문자 줄이기"), TTSConfig.partialRead).setDefaultValue(false).setSaveConsumer(v -> TTSConfig.partialRead = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("연속 채팅 이름 생략"), TTSConfig.skipRepeatNick).setDefaultValue(false).setSaveConsumer(v -> TTSConfig.skipRepeatNick = v).build());
            readOpts.addEntry(entry.startIntField(Component.literal("이름 다시 읽기 (초)"), TTSConfig.nickTimeout).setDefaultValue(10).setMin(1).setMax(300).setSaveConsumer(v -> TTSConfig.nickTimeout = v).build());
            readOpts.addEntry(entry.startBooleanToggle(Component.literal("긴 문장 자르기"), TTSConfig.trimLong).setDefaultValue(false).setSaveConsumer(v -> TTSConfig.trimLong = v).build());
            readOpts.addEntry(entry.startIntField(Component.literal("최대 글자 수"), TTSConfig.trimLength).setDefaultValue(80).setMin(5).setMax(500).setSaveConsumer(v -> TTSConfig.trimLength = v).build());

            // ===== 큐 옵션 =====
            ConfigCategory queueOpts = builder.getOrCreateCategory(Component.literal("큐 옵션"));
            queueOpts.addEntry(entry.startBooleanToggle(Component.literal("긴 대기열 자동 삭제"), TTSConfig.smartQueue).setDefaultValue(false).setSaveConsumer(v -> TTSConfig.smartQueue = v).build());
            queueOpts.addEntry(entry.startIntField(Component.literal("최대 대기열 수"), TTSConfig.maxQueue).setDefaultValue(10).setMin(1).setMax(100).setSaveConsumer(v -> TTSConfig.maxQueue = v).build());
            queueOpts.addEntry(entry.startBooleanToggle(Component.literal("새 채팅 우선모드"), TTSConfig.forceLatest).setDefaultValue(false).setSaveConsumer(v -> TTSConfig.forceLatest = v).build());

            // ===== 필터 =====
            ConfigCategory filterOpts = builder.getOrCreateCategory(Component.literal("필터"));
            filterOpts.addEntry(entry.startTextDescription(Component.literal("§e닉네임 차단 목록")).build());
            filterOpts.addEntry(new MultiLineListEntry(
                Component.literal("유저 차단 목록"),
                TTSConfig.nicknameFilter, new ArrayList<>())
                .setSaveConsumer(v -> TTSConfig.nicknameFilter = v));
            filterOpts.addEntry(entry.startTextDescription(Component.literal("§e단어 필터 목록")).build());
            filterOpts.addEntry(new MultiLineListEntry(
                Component.literal("단어 필터 목록"),
                TTSConfig.wordFilter, new ArrayList<>())
                .setSaveConsumer(v -> TTSConfig.wordFilter = v));
            filterOpts.addEntry(entry.startTextDescription(Component.literal("§7한 줄에 하나씩 입력하세요.")).build());

            // ===== 사용자별 목소리 =====
            ConfigCategory userVoiceOpts = builder.getOrCreateCategory(Component.literal("사용자별 목소리"));
            String engineList = String.join(", ", engines);
            List<String> userVoiceList = TTSConfig.userVoices.entrySet().stream()
                .map(e -> e.getKey() + "," + e.getValue())
                .sorted().collect(Collectors.toList());
            userVoiceOpts.addEntry(entry.startTextDescription(Component.literal("§e사용자별 목소리 목록")).build());
            userVoiceOpts.addEntry(new MultiLineListEntry(
                Component.literal("사용자별 목소리 목록"),
                userVoiceList, new ArrayList<>())
                .setSaveConsumer(list -> {
                    TTSConfig.userVoices.clear();
                    for (String s : list) {
                        String[] parts = s.split(",", 3);
                        if (parts.length >= 2 && !parts[0].isBlank()) {
                            TTSConfig.userVoices.put(
                                parts[0].strip().toLowerCase(),
                                parts[1].strip() + "," + (parts.length == 3 ? parts[2].strip() : "0")
                            );
                        }
                    }
                })
            );
            userVoiceOpts.addEntry(entry.startTextDescription(Component.literal("§7형식: 닉네임,목소리,속도  예) urakamza,Edge 남,10")).build());
            userVoiceOpts.addEntry(entry.startTextDescription(Component.literal("§7별칭: Google→ggl  Edge남→egm  Edge여→egf  SAPI→앞글자 일부")).build());
            userVoiceOpts.addEntry(entry.startTextDescription(Component.literal("§7사용 가능한 엔진: " + engineList)).build());
            userVoiceOpts.addEntry(entry.startTextDescription(Component.literal("§7한 줄에 하나씩 입력하세요.")).build());
            return builder.build();

        } catch (Exception e) {
            MCChatTTS.LOGGER.error("설정 화면 생성 오류: ", e);
            return parent;
        }
    }
}