package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.atualcombattraining.service.ActorVoiceMappingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对练对手音色映射（2026-11-10）。
 *
 * <p><b>为什么需要这层</b>：在此之前前端调 TTS 从不传 voiceId，SpeechTraining 一律回退
 * 默认音色 longwan_v2（女声），于是「赵启明」「王总」这些男性角色全部用女声说话，
 * 学员一听就出戏。音色必须和形象走同一套「性别 × 年龄段」维度。
 *
 * <p><b>与形象映射的关系</b>：{@code AvatarMappingServiceImpl} 已经把 actor.gender +
 * actor.ageRange 归一到 male/female 与 A1~A5；这里复用同一组归一化规则（同样的认词表、
 * 同样的「取下界落档」口径），保证同一条脚本选出来的脸和声音年龄感一致。
 *
 * <p><b>为什么要兜底</b>：老脚本（gender 字段引入前生成的）没有性别，案例快照也可能缺。
 * 此时退回默认音色而不是报错——声音不理想好过整轮没声音。
 */
@Slf4j
@Service
public class ActorVoiceMappingServiceImpl implements ActorVoiceMappingService {

    /** 无法判定性别时的兜底音色，与 SpeechTraining 的 speech.tts.default-voice 保持一致 */
    private static final String FALLBACK_VOICE = "longwan_v2";

    /**
     * CosyVoice v2 音色。女声三档、男声目前只有龙澄一个可用档，
     * 因此男声的青/中/老三档先共用 longcheng_v2；后续补音色只需改这张表。
     */
    private static final String FEMALE_YOUNG = "longxiaoxia_v2";
    private static final String FEMALE_MIDDLE = "longxiaochun_v2";
    private static final String FEMALE_SENIOR = "longwan_v2";
    private static final String MALE_DEFAULT = "longcheng_v2";

    /** 年龄档（与形象素材 A1~A5 对齐）：A1 20-28 / A2 29-36 / A3 37-45 / A4 46-55 / A5 56+ */
    private static final String[] AGE_BANDS = {"A1", "A2", "A3", "A4", "A5"};
    private static final int[] BAND_LOWER_BOUNDS = {20, 29, 37, 46, 56};

    @Override
    public String resolve(String gender, String ageRange) {
        String normalized = normalizeGender(gender);
        if (normalized == null) {
            // 没有性别依据时不猜：猜错声音比默认声音更出戏，直接兜底
            log.debug("角色性别未知，音色回退默认 {}", FALLBACK_VOICE);
            return FALLBACK_VOICE;
        }
        String band = bandOf(ageRange);
        return pickVoice(normalized, band);
    }

    /** 性别 + 年龄档 → 音色。年龄档为 null 时按「中年」处理（最不突兀的一档） */
    private String pickVoice(String gender, String band) {
        String effective = band == null ? "A3" : band;
        if ("female".equals(gender)) {
            return switch (effective) {
                case "A1", "A2" -> FEMALE_YOUNG;
                case "A3" -> FEMALE_MIDDLE;
                default -> FEMALE_SENIOR;
            };
        }
        // 男声目前只有一个音色可用，年龄段不影响结果；补了音色后在这里按档分流
        return MALE_DEFAULT;
    }

    /**
     * 性别归一化：与形象映射同一张认词表（男/男性/male/m/man/1、女/女性/female/f/woman/0）。
     * 认不出来返回 null，不默认成某个性别。
     */
    static String normalizeGender(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (text) {
            case "男", "男性", "male", "m", "man", "1" -> "male";
            case "女", "女性", "female", "f", "woman", "0" -> "female";
            default -> null;
        };
    }

    /**
     * 年龄区间 → 年龄档。与形象映射同口径：取区间下界落档，
     * 「40-45岁」落在 A3，保证同一输入永远同一结果；解析不出返回 null。
     */
    static String bandOf(String ageRange) {
        if (ageRange == null || ageRange.isBlank()) {
            return null;
        }
        int lower = firstNumber(ageRange.trim());
        if (lower <= 0) {
            return null;
        }
        int band = 0;
        for (int i = 0; i < BAND_LOWER_BOUNDS.length; i++) {
            if (lower >= BAND_LOWER_BOUNDS[i]) {
                band = i;
            }
        }
        return AGE_BANDS[band];
    }

    /** 取文本里第一个连续数字（"40-45岁" → 40） */
    private static int firstNumber(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        int end = start;
        while (end < text.length() && end - start < 3
                && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
            end++;
        }
        return end == start ? -1 : Integer.parseInt(text.substring(start, end));
    }
}
