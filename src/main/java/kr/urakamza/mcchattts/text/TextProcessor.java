package kr.urakamza.mcchattts.text;

import kr.urakamza.mcchattts.TTSConfig;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessor {

    private static final Map<String, String> JAMO_MAP = Map.ofEntries(
        Map.entry("ㄱ", "그"), Map.entry("ㄴ", "느"), Map.entry("ㄷ", "드"),
        Map.entry("ㄹ", "르"), Map.entry("ㅁ", "므"), Map.entry("ㅂ", "브"),
        Map.entry("ㅅ", "스"), Map.entry("ㅇ", "응"), Map.entry("ㅈ", "즈"),
        Map.entry("ㅊ", "츠"), Map.entry("ㅋ", "크"), Map.entry("ㅌ", "트"),
        Map.entry("ㅍ", "프"), Map.entry("ㅎ", "흐"), Map.entry("ㄲ", "끄"),
        Map.entry("ㄸ", "뜨"), Map.entry("ㅃ", "쁘"), Map.entry("ㅆ", "쓰"),
        Map.entry("ㅉ", "쯔"), Map.entry("ㅏ", "아"), Map.entry("ㅑ", "야"),
        Map.entry("ㅓ", "어"), Map.entry("ㅕ", "여"), Map.entry("ㅗ", "오"),
        Map.entry("ㅛ", "요"), Map.entry("ㅜ", "우"), Map.entry("ㅠ", "유"),
        Map.entry("ㅡ", "으"), Map.entry("ㅣ", "이"), Map.entry("ㅐ", "애"),
        Map.entry("ㅒ", "얘"), Map.entry("ㅔ", "에"), Map.entry("ㅖ", "예"),
        Map.entry("ㅘ", "와"), Map.entry("ㅙ", "왜"), Map.entry("ㅚ", "외"),
        Map.entry("ㅝ", "워"), Map.entry("ㅞ", "웨"), Map.entry("ㅟ", "위"),
        Map.entry("ㅢ", "의")
    );

    private static final Pattern COORD_PATTERN =
        Pattern.compile("(?<![\\d.])(\\d+)\\.\\d+(?![\\d.])");
    private static final Pattern REPEAT_PATTERN =
        Pattern.compile("(.)\\1{3,}");

    // 조사 패턴
    private static final Pattern JOSA_IGA    = Pattern.compile("이\\(가\\)");
    private static final Pattern JOSA_EULRUL = Pattern.compile("을\\(를\\)");
    private static final Pattern JOSA_EUNUN  = Pattern.compile("은\\(는\\)");
    private static final Pattern JOSA_GWAWA  = Pattern.compile("과\\(와\\)");
    private static final Pattern JOSA_EURO   = Pattern.compile("\\(으\\)로");

    /** 채팅 텍스트 전체 후처리 */
    public static String process(String text) {
        text = fixJosa(text);
        if (TTSConfig.forceLower)   text = text.toLowerCase();
        if (TTSConfig.removeCoord)  text = removeCoords(text);
        if (TTSConfig.useJamo)      text = replaceJamo(text);
        if (TTSConfig.partialRead)  text = reduceRepeats(text);
        if (TTSConfig.trimLong)     text = trim(text);
        return text;
    }

    /** 단어 필터 적용 */
    public static String applyWordFilter(String text) {
        if (!TTSConfig.wordFilter.isEmpty()) {
            for (String word : TTSConfig.wordFilter) {
                if (word.isBlank()) continue;
                text = text.replace(word, makeBeep(word));
            }
        }
        return text;
    }

    private static String fixJosa(String text) {
        record Rule(Pattern pattern, String withB, String withoutB, boolean isRo) {}
        Rule[] rules = {
            new Rule(JOSA_IGA,    "이",   "가",  false),
            new Rule(JOSA_EULRUL, "을",   "를",  false),
            new Rule(JOSA_EUNUN,  "은",   "는",  false),
            new Rule(JOSA_GWAWA,  "과",   "와",  false),
            new Rule(JOSA_EURO,   "으로", "로",  true),
        };

        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                sb.append(text, last, matcher.start());
                String prev = text.substring(0, matcher.start()).strip();
                if (prev.isEmpty()) {
                    sb.append(matcher.group());
                    last = matcher.end();
                    continue;
                }
                char lastChar = prev.charAt(prev.length() - 1);
                boolean hasBatchim = false;

                if (lastChar >= '가' && lastChar <= '힣') {
                    hasBatchim = (lastChar - 0xAC00) % 28 > 0;
                } else if (Character.isDigit(lastChar)) {
                    if (rule.isRo()) hasBatchim = "036".indexOf(lastChar) >= 0;
                    else             hasBatchim = "013678".indexOf(lastChar) >= 0;
                }

                String selected;
                if (rule.isRo()) {
                    if (Character.isDigit(lastChar) && "1782459".indexOf(lastChar) >= 0)
                        selected = rule.withoutB();
                    else if (lastChar >= '가' && lastChar <= '힣' && (lastChar - 0xAC00) % 28 == 8)
                        selected = rule.withoutB(); // ㄹ 받침
                    else
                        selected = hasBatchim ? rule.withB() : rule.withoutB();
                } else {
                    selected = hasBatchim ? rule.withB() : rule.withoutB();
                }

                sb.append(selected);
                last = matcher.end();
            }
            sb.append(text.substring(last));
            text = sb.toString();
        }
        return text;
    }

    private static String removeCoords(String text) {
        return COORD_PATTERN.matcher(text).replaceAll("$1");
    }

    private static String replaceJamo(String text) {
        for (Map.Entry<String, String> e : JAMO_MAP.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    private static String reduceRepeats(String text) {
        return REPEAT_PATTERN.matcher(text).replaceAll("$1$1$1…");
    }

    private static String trim(String text) {
        int limit = Math.max(5, TTSConfig.trimLength);
        if (text.length() > limit) {
            return text.substring(0, limit) + "…";
        }
        return text;
    }

    private static String makeBeep(String word) {
        if (word.length() <= 1) return "삐";
        return "삐" + "비".repeat(word.length() - 1);
    }
}