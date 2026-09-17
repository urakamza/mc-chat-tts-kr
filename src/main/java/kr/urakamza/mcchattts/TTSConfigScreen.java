package kr.urakamza.mcchattts;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import kr.urakamza.mcchattts.tts.TTSManager;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Vanilla widgets only. Edits stay local until Save is pressed. */
@Environment(EnvType.CLIENT)
public final class TTSConfigScreen extends Screen {
    private record Label(String text, int y, int color) {}
    private record Placement(AbstractWidget widget, int y) {}
    private record NumberOption(String key, String label, int min, int max, int defaultValue) {}
    private static final List<NumberOption> NUMBERS = List.of(
        new NumberOption("speed", "속도 (Google 제외)", -50, 50, 0),
        new NumberOption("volume", "볼륨 (%)", 0, 100, 100),
        new NumberOption("nickTimeout", "이름 다시 읽기 (초)", 1, 300, 10),
        new NumberOption("trimLength", "최대 글자 수", 5, 500, 80),
        new NumberOption("maxQueue", "최대 대기열 수", 1, 100, 10)
    );
    private final Screen parent;
    private final Map<KeyMapping, InputConstants.Key> draftKeys = new LinkedHashMap<>();
    private final Map<KeyMapping, Button> keyButtons = new LinkedHashMap<>();
    private KeyMapping capturingKey;
    private Draft draft = new Draft(false);
    private ScrollingPane content;
    private ScrollingPane dropdown;
    private Button engineButton;
    private List<Button> engineOptions = List.of();
    private int selectedEngineIndex;
    private int left;
    private int panelWidth;
    private int controlWidth;
    private int row;
    private String error = "";

    private TTSConfigScreen(Screen parent) {
        super(Component.literal("MC Chat TTS 설정"));
        this.parent = parent;
        for (KeyMapping mapping : List.of(MCChatTTSClient.keySkip, MCChatTTSClient.keyStop, MCChatTTSClient.keyConfig)) {
            draftKeys.put(mapping, InputConstants.getKey(mapping.saveString()));
        }
    }

    public static Screen create(Screen parent) {
        return new TTSConfigScreen(parent);
    }

    @Override
    protected void init() {
        double previousScroll = content == null ? 0 : content.scrollAmount();
        dropdown = null;
        capturingKey = null;
        keyButtons.clear();
        panelWidth = Math.min(440, width - 24);
        left = (width - panelWidth) / 2;
        controlWidth = panelWidth - 20;
        content = addRenderableWidget(new ScrollingPane(left, 32, panelWidth,
            Math.max(40, height - 92), "TTS 설정 목록", false));
        row = 8;
        section("기본 설정");
        toggle("enabled", "TTS 활성화");
        engineButton = contentButton(engineLabel(), b -> openDropdown());
        engineButton.setTooltip(Tooltip.create(Component.literal("목록에서 엔진 선택: " + draft.engine)));
        number("speed");
        number("volume");
        section("단축키");
        help("버튼을 누른 뒤 키 또는 마우스 버튼을 입력하세요.");
        help("Esc: 지정 취소 · Backspace/Delete: 할당 해제");
        keyBinding(MCChatTTSClient.keySkip, "현재 음성 스킵");
        keyBinding(MCChatTTSClient.keyStop, "전체 중단");
        keyBinding(MCChatTTSClient.keyConfig, "설정창 열기");
        refreshKeyButtons();

        section("읽기 옵션");
        toggle("readNick", "이름 읽기");
        toggle("readSystem", "시스템 메시지 읽기");
        toggle("forceLower", "소문자로 강제 변환");
        toggle("removeCoord", "시스템 메시지 소수 자릿수 줄이기");
        toggle("useJamo", "자모음 읽기 보정");
        toggle("partialRead", "반복 문자 줄이기");
        toggle("skipRepeatNick", "연속 채팅 이름 생략");
        number("nickTimeout");
        toggle("trimLong", "긴 문장 자르기");
        number("trimLength");

        section("대기열");
        toggle("smartQueue", "긴 대기열 자동 삭제");
        number("maxQueue");
        toggle("forceLatest", "새 채팅 우선모드");

        section("닉네임 차단");
        help("한 줄에 닉네임 하나씩 · 대소문자를 구분합니다.");
        multiline("닉네임 차단 목록", draft.nicknames, v -> draft.nicknames = v);
        section("단어 필터");
        help("한 줄에 단어 하나씩 · 대소문자를 구분하지 않습니다.");
        multiline("단어 필터 목록", draft.words, v -> draft.words = v);
        section("사용자별 목소리");
        help("닉네임,목소리,속도  예: urakamza,egm,10");
        help("닉네임은 실제 대소문자와 정확히 맞춰 주세요.");
        help("ggl: Google / egm: Edge 남 / egf: Edge 여");
        help("SAPI: 음성 이름 · 속도 생략 시 0");
        multiline("사용자별 목소리 목록", draft.voices, v -> draft.voices = v);
        content.totalHeight = row + 8;
        content.setScrollAmount(previousScroll);

        int third = (panelWidth - 8) / 3;
        footerButton("전체 기본값", left, third, b -> {
            draft = new Draft(true);
            draftKeys.replaceAll((mapping, key) -> mapping.getDefaultKey());
            error = "";
            rebuildWidgets();
        });
        footerButton("취소", left + third + 4, third, b -> onClose());
        footerButton("저장", left + (third + 4) * 2, third, b -> save());
    }

    private void keyBinding(KeyMapping mapping, String label) {
        int y = nextRow();
        content.labels.add(new Label(label, y + 6, 0xFFFFFFFF));
        int buttonWidth = Math.min(140, controlWidth / 2);
        Button button = Button.builder(Component.empty(), b -> {
            capturingKey = mapping;
            refreshKeyButtons();
        }).bounds(left + 6 + controlWidth - buttonWidth, 0, buttonWidth, 20).build();
        keyButtons.put(mapping, button);
        content.add(button, y);
    }

    private void refreshKeyButtons() {
        keyButtons.forEach((mapping, button) -> {
            InputConstants.Key key = draftKeys.get(mapping);
            boolean conflict = !key.equals(InputConstants.UNKNOWN) && Arrays.stream(minecraft.options.keyMappings)
                .anyMatch(other -> other != mapping && key.equals(draftKeys.getOrDefault(other,
                    InputConstants.getKey(other.saveString()))));
            Component label = capturingKey == mapping
                ? Component.literal("> 입력 대기 <").withStyle(style -> style.withColor(0xFFFF66))
                : key.getDisplayName().copy().withStyle(style -> style.withColor(conflict ? 0xF0C2C2 : 0xFFFFFF));
            button.setMessage(label);
            button.setTooltip(Tooltip.create(Component.literal(conflict
                ? "다른 동작과 키가 겹칩니다. 저장하면 그대로 적용됩니다."
                : "클릭 후 원하는 키 입력 · 저장 시 적용")));
        });
    }

    private void finishKeyCapture(InputConstants.Key key) {
        if (key != null) draftKeys.put(capturingKey, key);
        capturingKey = null;
        refreshKeyButtons();
    }

    private String engineLabel() {
        return "엔진: " + draft.engine + "  ▼";
    }

    private int nextRow() {
        int y = row;
        row += 24;
        return y;
    }

    private void section(String title) {
        row += 10;
        content.labels.add(new Label(title, row, 0xFFFFCC66));
        row += 20;
    }

    private void help(String text) {
        // Wrap long help text at small GUI sizes, using the actual game font.
        for (String remaining = text; !remaining.isEmpty();) {
            String line = font.plainSubstrByWidth(remaining, controlWidth);
            if (line.isEmpty()) break;
            content.labels.add(new Label(line, row, 0xFFBBBBBB));
            remaining = remaining.substring(line.length());
            row += 12;
        }
        row += 3;
    }

    private Button contentButton(String text, Button.OnPress action) {
        Button button = Button.builder(Component.literal(text), action)
            .bounds(left + 6, 0, controlWidth, 20).build();
        content.add(button, nextRow());
        return button;
    }

    private void footerButton(String text, int x, int w, Button.OnPress action) {
        addRenderableWidget(Button.builder(Component.literal(text), action)
            .bounds(x, height - 28, w, 20).build());
    }

    private void toggle(String key, String label) {
        int y = nextRow();
        content.labels.add(new Label(label, y + 6, 0xFFFFFFFF));
        Button button = Button.builder(toggleText(key), b -> {
            draft.flags.put(key, !draft.flags.get(key));
            b.setMessage(toggleText(key));
        }).bounds(left + 6 + controlWidth - 70, 0, 70, 20).build();
        button.setTooltip(Tooltip.create(Component.literal(label)));
        content.add(button, y);
    }

    private Component toggleText(String key) {
        boolean on = draft.flags.get(key);
        return Component.literal(on ? "켜짐" : "꺼짐")
            .withStyle(style -> style.withColor(on ? 0xC9E8AE : 0xF0C2C2));
    }

    private void number(String key) {
        NumberOption spec = NUMBERS.stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
        int y = nextRow();
        content.labels.add(new Label(spec.label(), y + 6, 0xFFFFFFFF));
        Draft editingDraft = draft;
        EditBox box = new EditBox(font, left + 6 + controlWidth - 70, 0, 70, 20, Component.literal(spec.label())) {
            @Override
            public void setFocused(boolean focused) {
                if (!focused) {
                    // Allow intermediate input such as "-" while typing a negative number.
                    try {
                        String corrected = Integer.toString(clampNumber(getValue(), spec));
                        if (!corrected.equals(getValue())) setValue(corrected);
                    } catch (NumberFormatException ignored) {
                        // Empty/non-numeric input is reported when Save is pressed.
                    }
                }
                super.setFocused(focused);
            }
        };
        box.setMaxLength(12);
        box.setValue(editingDraft.numbers.get(key));
        box.setTooltip(Tooltip.create(Component.literal(spec.min() + " ~ " + spec.max() + " (범위 초과 시 자동 보정)")));
        box.setResponder(v -> editingDraft.numbers.put(key, v));
        content.add(box, y);
    }

    private static int clampNumber(String text, NumberOption spec) {
        long value = Long.parseLong(text.strip());
        return (int) Math.max(spec.min(), Math.min((long) spec.max(), value));
    }

    private void multiline(String label, String value, Consumer<String> listener) {
        MultiLineEditBox box = MultiLineEditBox.builder()
            .setX(left + 6).setY(0)
            .setPlaceholder(Component.literal("한 줄에 하나씩..."))
            .build(font, controlWidth, 88, Component.literal(label));
        box.setValue(value);
        box.setValueListener(listener);
        content.add(box, row);
        row += 96;
    }

    private void openDropdown() {
        if (dropdown != null) return;
        content.reveal(engineButton);
        LinkedHashSet<String> choices = new LinkedHashSet<>(List.of("Google", "Edge 남", "Edge 여"));
        List<String> installed = TTSConfig.sapiVoices;
        if (installed != null) {
            for (String voice : installed) if (voice != null && !voice.isBlank()) choices.add(voice);
        }
        // Keep a previously configured voice selectable while SAPI discovery is pending.
        choices.add(draft.engine);
        int below = content.getBottom() - engineButton.getBottom() - 2;
        int above = engineButton.getY() - content.getY() - 2;
        boolean opensBelow = below >= Math.min(choices.size() * 22 + 6, 90) || below >= above;
        int menuHeight = Math.min(choices.size() * 22 + 6, Math.min(150, Math.max(26, opensBelow ? below : above)));
        int y = opensBelow ? engineButton.getBottom() + 2 : engineButton.getY() - menuHeight - 2;
        dropdown = addRenderableWidget(new ScrollingPane(engineButton.getX(), y,
            engineButton.getWidth(), menuHeight, "TTS 엔진 선택", true));
        engineOptions = new ArrayList<>();
        int index = 0;
        for (String choice : choices) {
            Button option = Button.builder(Component.literal((choice.equals(draft.engine) ? "● " : "  ") + choice), b -> {
                draft.engine = choice;
                engineButton.setMessage(Component.literal(engineLabel()));
                engineButton.setTooltip(Tooltip.create(Component.literal("목록에서 엔진 선택: " + draft.engine)));
                closeDropdown();
            }).bounds(dropdown.getX() + 3, 0, dropdown.getWidth() - 16, 20).build();
            option.setTooltip(Tooltip.create(Component.literal(choice)));
            dropdown.add(option, 3 + index * 22);
            engineOptions.add(option);
            if (choice.equals(draft.engine)) selectedEngineIndex = index;
            index++;
        }
        dropdown.totalHeight = choices.size() * 22 + 6;
        dropdown.setScrollAmount(0);
    }

    private void focusDropdown() {
        setDragging(false);
        content.setDragging(false);
        setFocused(dropdown);
        if (dropdown.getFocused() == null) dropdown.setFocused(engineOptions.get(selectedEngineIndex));
    }

    private void closeDropdown() {
        ScrollingPane old = dropdown;
        dropdown = null;
        if (old != null) {
            old.setFocused((GuiEventListener) null);
            removeWidget(old);
            setDragging(false);
            content.setDragging(false);
            setFocused(content);
            content.setFocused(engineButton);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (capturingKey != null) {
            finishKeyCapture(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        if (dropdown != null) {
            if (dropdown.isMouseOver(event.x(), event.y())) dropdown.mouseClicked(event, doubleClick);
            else closeDropdown();
            return true; // Dismissing the menu must not activate a setting underneath it.
        }
        boolean handled = super.mouseClicked(event, doubleClick);
        if (dropdown != null) focusDropdown();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (dropdown != null) {
            if (dropdown.isMouseOver(x, y)) dropdown.mouseScrolled(x, y, horizontal, vertical);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dropdown != null) {
            dropdown.mouseDragged(event, dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dropdown != null) {
            dropdown.mouseReleased(event);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingKey != null) {
            if (event.isEscape()) finishKeyCapture(null);
            else if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE)
                finishKeyCapture(InputConstants.UNKNOWN);
            else finishKeyCapture(MCChatTTSClient.keyConfig.getDefaultKey().getType().getOrCreate(event.key()));
            return true;
        }
        if (dropdown != null) {
            if (event.isEscape() || event.key() == InputConstants.KEY_TAB) {
                closeDropdown();
            } else if (event.key() == InputConstants.KEY_DOWN || event.key() == InputConstants.KEY_UP) {
                int current = engineOptions.indexOf(dropdown.getFocused());
                selectedEngineIndex = Math.floorMod(current + (event.key() == InputConstants.KEY_DOWN ? 1 : -1), engineOptions.size());
                dropdown.setFocused(engineOptions.get(selectedEngineIndex));
            } else if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER
                    || event.key() == InputConstants.KEY_SPACE) {
                int current = engineOptions.indexOf(dropdown.getFocused());
                engineOptions.get(current < 0 ? selectedEngineIndex : current).onPress(event);
            }
            return true;
        }
        boolean handled = super.keyPressed(event);
        if (dropdown != null) focusDropdown();
        return handled;
    }

    /** A clipped vanilla container with a draggable scrollbar and focus-driven scrolling. */
    private final class ScrollingPane extends AbstractContainerWidget {
        private final List<Placement> placements = new ArrayList<>();
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private final List<Label> labels = new ArrayList<>();
        private final boolean popup;
        private int totalHeight;

        ScrollingPane(int x, int y, int w, int h, String label, boolean popup) {
            super(x, y, w, h, Component.literal(label), defaultSettings(6));
            this.popup = popup;
        }

        void add(AbstractWidget widget, int relativeY) {
            placements.add(new Placement(widget, relativeY));
            widgets.add(widget);
            widget.setY(getY() + relativeY - (int) scrollAmount());
        }

        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override protected int contentHeight() { return totalHeight; }
        @Override protected double scrollRate() { return 24; }

        @Override
        public void setScrollAmount(double value) {
            super.setScrollAmount(value);
            if (placements != null) {
                for (Placement p : placements) p.widget().setY(getY() + p.y() - (int) scrollAmount());
            }
        }

        void reveal(AbstractWidget widget) {
            if (widget.getY() < getY() + 2) setScrollAmount(scrollAmount() + widget.getY() - getY() - 2);
            else if (widget.getBottom() > getBottom() - 2) setScrollAmount(scrollAmount() + widget.getBottom() - getBottom() + 2);
        }

        @Override
        public void setFocused(GuiEventListener listener) {
            super.setFocused(listener);
            if (listener instanceof AbstractWidget widget) reveal(widget);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return isMouseOver(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            if (!isMouseOver(x, y)) return false;
            for (AbstractWidget widget : widgets) {
                if (widget instanceof MultiLineEditBox box && box.isMouseOver(x, y)) {
                    double before = box.scrollAmount();
                    box.mouseScrolled(x, y, horizontal, vertical);
                    if (before != box.scrollAmount()) return true;
                    break; // At the editor's edge, continue scrolling the outer settings list.
                }
            }
            return super.mouseScrolled(x, y, horizontal, vertical);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            if (popup) g.nextStratum();
            g.fill(getX(), getY(), getRight(), getBottom(), popup ? 0xFF171717 : 0x66000000);
            if (popup) g.outline(getX(), getY(), getWidth(), getHeight(), 0xFF999999);
            g.enableScissor(getX(), getY(), getRight() - 8, getBottom());
            int mouseX = isMouseOver(mx, my) && (popup || dropdown == null) ? mx : -10000;
            int mouseY = mouseX == -10000 ? -10000 : my;
            for (Label label : labels) {
                int y = getY() + label.y() - (int) scrollAmount();
                if (y + 10 > getY() && y < getBottom()) g.text(font, label.text(), getX() + 6, y, label.color(), true);
            }
            for (AbstractWidget widget : widgets) {
                if (widget.getBottom() > getY() && widget.getY() < getBottom()) {
                    widget.extractRenderState(g, mouseX, mouseY, delta);
                }
            }
            g.disableScissor();
            extractScrollbar(g, mx, my);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            if (getFocused() instanceof AbstractWidget widget) widget.updateNarration(output.nest());
        }
    }

    private void save() {
        try {
            // Finish the active number edit, then validate before applying configuration.
            content.setFocused((GuiEventListener) null);
            Map<String, Integer> values = new HashMap<>();
            for (NumberOption spec : NUMBERS) {
                int value;
                try { value = clampNumber(draft.numbers.get(spec.key()), spec); }
                catch (NumberFormatException e) { throw new IllegalArgumentException(spec.label() + ": 정수를 입력하세요."); }
                draft.numbers.put(spec.key(), Integer.toString(value));
                values.put(spec.key(), value);
            }
            Map<String, String> voices = parseVoices(draft.voices);
            TTSConfig.enabled = draft.flags.get("enabled");
            TTSConfig.engine = draft.engine;
            TTSConfig.speed = values.get("speed");
            TTSConfig.volume = values.get("volume") / 100.0f;
            TTSConfig.readNick = draft.flags.get("readNick");
            TTSConfig.readSystem = draft.flags.get("readSystem");
            TTSConfig.forceLower = draft.flags.get("forceLower");
            TTSConfig.removeCoord = draft.flags.get("removeCoord");
            TTSConfig.useJamo = draft.flags.get("useJamo");
            TTSConfig.partialRead = draft.flags.get("partialRead");
            TTSConfig.skipRepeatNick = draft.flags.get("skipRepeatNick");
            TTSConfig.nickTimeout = values.get("nickTimeout");
            TTSConfig.trimLong = draft.flags.get("trimLong");
            TTSConfig.trimLength = values.get("trimLength");
            TTSConfig.smartQueue = draft.flags.get("smartQueue");
            TTSConfig.maxQueue = values.get("maxQueue");
            TTSConfig.forceLatest = draft.flags.get("forceLatest");
            TTSConfig.nicknameFilter = lines(draft.nicknames);
            TTSConfig.wordFilter = lines(draft.words);
            TTSConfig.userVoices = voices;
            TTSManager.setAccepting(TTSConfig.enabled && minecraft.player != null);
            draftKeys.forEach((mapping, key) -> {
                mapping.setKey(key);
                mapping.setDown(false);
                while (mapping.consumeClick()) { }
            });
            KeyMapping.resetMapping();
            minecraft.options.save();
            TTSConfig.save();
            onClose();
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
    }

    private static List<String> lines(String text) {
        return Arrays.stream(text.split("\\R")).map(String::strip)
            .filter(s -> !s.isEmpty()).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Map<String, String> parseVoices(String text) {
        Map<String, String> result = new HashMap<>();
        int line = 0;
        for (String raw : text.split("\\R")) {
            line++;
            if (raw.isBlank()) continue;
            String[] parts = raw.split(",", -1);
            String problem = "목소리 " + line + "행: 닉네임,목소리,속도 형식을 확인하세요.";
            if (parts.length < 2 || parts.length > 3 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(problem);
            }
            int speed = 0;
            if (parts.length == 3) {
                try { speed = Integer.parseInt(parts[2].strip()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException(problem); }
            }
            if (speed < -50 || speed > 50) {
                throw new IllegalArgumentException("목소리 " + line + "행: 속도는 -50 ~ 50입니다.");
            }
            result.put(parts[0].strip(), parts[1].strip() + "," + speed);
        }
        return result;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, title.getString(), 9, 0xFFFFFFFF);
        String status = error.isEmpty() ? "저장하면 적용됩니다. Esc: 취소" : error;
        graphics.textWithWordWrap(font, Component.literal(status), left, height - 55,
            panelWidth, error.isEmpty() ? 0xFFAAAAAA : 0xFFFF7777, true);
    }

    private void centered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(font, text, (width - font.width(text)) / 2, y, color, true);
    }

    private static final class Draft {
        final Map<String, Boolean> flags = new HashMap<>();
        final Map<String, String> numbers = new HashMap<>();
        String engine;
        String nicknames;
        String words;
        String voices;

        Draft(boolean defaults) {
            flags.put("enabled", defaults || TTSConfig.enabled);
            flags.put("readNick", defaults || TTSConfig.readNick);
            flags.put("readSystem", defaults || TTSConfig.readSystem);
            flags.put("forceLower", defaults || TTSConfig.forceLower);
            flags.put("removeCoord", defaults || TTSConfig.removeCoord);
            flags.put("useJamo", defaults || TTSConfig.useJamo);
            flags.put("partialRead", !defaults && TTSConfig.partialRead);
            flags.put("skipRepeatNick", !defaults && TTSConfig.skipRepeatNick);
            flags.put("trimLong", !defaults && TTSConfig.trimLong);
            flags.put("smartQueue", !defaults && TTSConfig.smartQueue);
            flags.put("forceLatest", !defaults && TTSConfig.forceLatest);
            numbers.put("speed", Integer.toString(TTSConfig.speed));
            numbers.put("volume", Integer.toString(Math.round(TTSConfig.volume * 100)));
            numbers.put("nickTimeout", Integer.toString(TTSConfig.nickTimeout));
            numbers.put("trimLength", Integer.toString(TTSConfig.trimLength));
            numbers.put("maxQueue", Integer.toString(TTSConfig.maxQueue));
            if (defaults) NUMBERS.forEach(n -> numbers.put(n.key(), Integer.toString(n.defaultValue())));
            engine = defaults ? "Google" : TTSConfig.engine;
            nicknames = defaults ? "" : String.join("\n", TTSConfig.nicknameFilter);
            words = defaults ? "" : String.join("\n", TTSConfig.wordFilter);
            voices = defaults ? "" : TTSConfig.userVoices.entrySet().stream()
                .map(e -> e.getKey() + "," + e.getValue()).sorted().collect(Collectors.joining("\n"));
        }
    }
}
