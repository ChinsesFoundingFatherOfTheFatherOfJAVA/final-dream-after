package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数字人形象定图实现（预置素材库按角色属性映射，2026-11-05）。
 * <p>
 * 素材来源：t1 产出的 60 张 WebP（清单 `tmp/avatar-assets.json`），
 * 落盘 `app.upload.avatar-dir`（当前 `/avatar`，即 `D:\avatar`），
 * 库内 URL 形如 `/avatar/m-A3-04.webp`，浏览器经网关白名单 `/api/avatar/**` 访问。
 * <p>
 * <b>为什么池是代码常量而不是查库</b>：设计 §5.3 的 `act_avatar_asset` 表是"可选、建议后续做"；
 * 本期素材只有 60 张固定文件，查库只会给发布链路多加一次查询和一张表。改成常量后，
 * 定图是纯函数——同样的角色属性在任何环境都得到同一张图，也便于单测直接锁住。
 * <p>
 * <b>N=6 必须首期定死</b>（设计 §7.2 B5）：{@code seq = hash % pool.length}，
 * 池大小一变，所有已分配角色全量换脸。所以每组合 6 张写成常量 {@link #IMAGES_PER_GROUP}，
 * <b>不要改成运行期可配</b>；扩容属于行为变更，要单独评审。
 */
@Slf4j
@Service
public class AvatarMappingServiceImpl implements AvatarMappingService {

    /**
     * 每个（性别 × 年龄档）组合的素材张数，首期定死 <b>6</b>（用户决策 P5'=b，共 2×5×6=60 张）。
     * <p>
     * 这个数字直接决定哈希池大小：`seq = hash % pool.length`。改成 5 或 12 会让
     * <b>所有已发布主题的角色换脸</b>，属行为变更而非数据变更，不得改动。
     */
    static final int IMAGES_PER_GROUP = 6;

    /** 年龄档（设计 §8.1，P5=b 的 5 档）；顺序即素材命名里的 A1~A5 */
    private static final String[] AGE_BANDS = {"A1", "A2", "A3", "A4", "A5"};

    /** 性别目录名：m=male / f=female（与素材文件命名 `{g}-{band}-{NN}.webp` 一致） */
    private static final String GENDER_DIR_MALE = "m";
    private static final String GENDER_DIR_FEMALE = "f";

    /** 素材 URL 前缀。/avatar 由 SpeechTraining 静态托管，网关映射到 /api/avatar/** */
    private static final String AVATAR_URL_PREFIX = "/avatar/";

    private static final String GENDER_MALE = "male";
    private static final String GENDER_FEMALE = "female";

    /**
     * 全部 60 张素材的相对 URL（按 性别 → 年龄档 → 序号 生成，顺序稳定）。
     * <p>
     * 生成顺序固定是为了「L2 丢年龄档」与「L4 场景默认图」的池顺序可复现：
     * 池顺序一变，同一 hash 会落到另一张图。所以这里不用 Map/HashSet，只用 List。
     */
    private static final List<String> ALL_ASSETS = buildAllAssets();

    /** 年龄档区间下界（含），与 t1 清单里的 ageBand 定义一一对应 */
    private static final int[] BAND_LOWER_BOUNDS = {20, 29, 37, 46, 56};

    private static List<String> buildAllAssets() {
        List<String> assets = new ArrayList<>(2 * AGE_BANDS.length * IMAGES_PER_GROUP);
        for (String genderDir : List.of(GENDER_DIR_MALE, GENDER_DIR_FEMALE)) {
            for (String band : AGE_BANDS) {
                for (int index = 1; index <= IMAGES_PER_GROUP; index++) {
                    assets.add(AVATAR_URL_PREFIX + genderDir + "-" + band + "-"
                            + (index < 10 ? "0" + index : String.valueOf(index)) + ".webp");
                }
            }
        }
        return List.copyOf(assets);
    }

    @Override
    public Resolved resolve(AvatarQuery query) {
        if (query == null) {
            return Resolved.none();
        }
        // L0 上传覆盖：管理端指定的图优先于任何自动映射（P4=a）
        if (hasText(query.overrideUrl())) {
            return new Resolved(query.overrideUrl().trim(), Level.L0_UPLOAD);
        }

        // 性别与年龄档都先规范化：模型给的是「男」「40-45岁」，素材按 male/female + A1~A5 组织
        String gender = normalizeGender(query.gender());
        String ageBand = bandOf(query.ageRange());
        String seed = hashSeed(query.actorName(), query.identity());

        // L1 精确：性别 + 年龄档 → 6 张池
        if (gender != null && ageBand != null) {
            List<String> pool = assetsOf(gender, ageBand);
            return new Resolved(pick(pool, seed), Level.L1_EXACT);
        }

        // L2 丢年龄档：只有性别 → 该性别 5 个档共 30 张成池
        if (gender != null) {
            List<String> pool = assetsOf(gender, null);
            return new Resolved(pick(pool, seed), Level.L2_GENDER_ONLY);
        }

        // L3 猜性别：脚本连 gender 都没产出（正常生成不会发生，见设计 §6.1）→ 按 presetType / 身份关键词猜
        String guessed = guessGender(query.presetType(), query.identity());
        if (guessed != null) {
            List<String> pool = assetsOf(guessed, ageBand);
            // 年龄档已知就用「猜到的性别 + 该档」，否则用该性别全档
            return new Resolved(pick(pool, seed), Level.L3_GUESSED_GENDER);
        }

        // L4 场景默认图：同一 scenarioId 恒定映射一张（保证同场景视觉稳定，不随发布批次变化）
        if (hasText(query.scenarioId())) {
            return new Resolved(pick(ALL_ASSETS, hashHex(query.scenarioId().trim())),
                    Level.L4_SCENE_DEFAULT);
        }

        // 兜底：连场景都没有 → null，前端用「角色名首字圆形」占位（现网已有，零改动）
        return Resolved.none();
    }

    @Override
    public ActorProfile readActor(ObjectNode script) {
        if (script == null) {
            return ActorProfile.empty();
        }
        ObjectNode actor = JsonSupport.child(script, "actor");
        if (actor != null) {
            // drill-script-v1 形态（随机/自定义）：actor 子对象
            return new ActorProfile(
                    JsonSupport.text(actor, "name"),
                    JsonSupport.text(actor, "identity"),
                    JsonSupport.text(actor, "gender"),
                    JsonSupport.text(actor, "ageRange"));
        }
        // 案例正文形态（发布快照的扁平字段）：actorName / actorPosition / actorGender / actorAgeRange
        return new ActorProfile(
                JsonSupport.text(script, "actorName"),
                JsonSupport.text(script, "actorPosition"),
                JsonSupport.text(script, "actorGender"),
                JsonSupport.text(script, "actorAgeRange"));
    }

    @Override
    public void writeAvatarUrl(ObjectNode script, String avatarUrl) {
        if (script == null || !hasText(avatarUrl)) {
            // 不造空键：后端读不到就交给前端占位，避免"定了但为空"这种第三种状态
            return;
        }
        String url = avatarUrl.trim();
        ObjectNode actor = JsonSupport.child(script, "actor");
        if (actor != null) {
            JsonSupport.putText(actor, "avatarUrl", url);
            return;
        }
        // 案例正文扁平字段：与 actorName/actorPosition 同层级，运行时投影直读这个键
        JsonSupport.putText(script, "actorAvatarUrl", url);
    }

    // ------------------------------------------------------------------ 池与取图

    /** 某性别（可选某年龄档）的素材池；顺序稳定（年龄档 A1→A5、序号 01→06） */
    private List<String> assetsOf(String gender, String ageBand) {
        String dir = GENDER_MALE.equals(gender) ? GENDER_DIR_MALE : GENDER_DIR_FEMALE;
        List<String> pool = new ArrayList<>();
        for (String band : AGE_BANDS) {
            if (ageBand != null && !ageBand.equals(band)) {
                continue;
            }
            for (int index = 1; index <= IMAGES_PER_GROUP; index++) {
                pool.add(AVATAR_URL_PREFIX + dir + "-" + band + "-"
                        + (index < 10 ? "0" + index : String.valueOf(index)) + ".webp");
            }
        }
        return pool;
    }

    /** 用稳定哈希在池里定一张；池为空返回 null（不拿别的池顶替） */
    private String pick(List<String> pool, String hashHexOrSeed) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        long bucket = bucketOf(hashHexOrSeed);
        int index = (int) (bucket % pool.size());
        return pool.get(index);
    }

    /**
     * 取图序号 = SHA-256(seed) 前 4 字节的无符号值。
     * <p>
     * <b>为什么用 SHA-256 而不是 String.hashCode()</b>（设计 §7.2 B4）：哈希必须跨进程、
     * 跨语言、跨版本稳定。`String.hashCode()` 依赖 JVM 的字符串散列实现（JDK 规范虽定义了算法，
     * 但这里刻意不依赖"语言内建行为"这一假设），`Objects.hash()` 会掺入 Object 身份不可复现，
     * 随机数更是直接破坏「重练沿用同图」。
     */
    private long bucketOf(String seed) {
        byte[] digest = sha256(seed);
        long value = ((long) (digest[0] & 0xFF) << 24)
                | ((long) (digest[1] & 0xFF) << 16)
                | ((long) (digest[2] & 0xFF) << 8)
                | (long) (digest[3] & 0xFF);
        return value & 0xFFFFFFFFL;
    }

    /** 角色名 + 身份 拼成哈希种子（设计 §7.2 B3：只用名字太弱，"王总"在多个主题都会出现） */
    private String hashSeed(String actorName, String identity) {
        String name = actorName == null ? "" : actorName.trim();
        String role = identity == null ? "" : identity.trim();
        return name + "|" + role;
    }

    /** 直接对任意字符串做 SHA-256（L4 场景默认图用，保证同场景恒定） */
    private String hashHex(String text) {
        byte[] digest = sha256(text);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求支持的算法，走到这里说明运行环境不完整，必须显式失败
            throw new IllegalStateException("SHA-256 不可用，无法做稳定定图", e);
        }
    }

    // ------------------------------------------------------------------ 属性规范化

    /**
     * 性别归一化：模型/案例可能给「男」「女」「male」「female」「m」「f」「男性」「女性」。
     * 认不出来返回 null（交给 L3 猜），<b>不默认成某个性别</b>——猜错比没有更糟。
     */
    static String normalizeGender(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "男", "男性", "male", "m", "man", "1" -> GENDER_MALE;
            case "女", "女性", "female", "f", "woman", "0" -> GENDER_FEMALE;
            default -> null;
        };
    }

    /**
     * 年龄区间 → 年龄档（A1..A5）。
     * <p>
     * 取<b>区间下界</b>落档：模型给的是「40-45岁」「40-50岁」这类文本，素材档是
     * A1 20-28 / A2 29-36 / A3 37-45 / A4 46-55 / A5 56+。取下界能保证
     * 「40-45」「40-50」这类跨档区间稳定落在更年轻的那一档（与 t1 素材的年龄观感一致），
     * 且同一输入永远同一结果。
     * <p>
     * 解析不出来（如「中年」「35岁左右」无区间）返回 null → 触发 L2 丢年龄档。
     */
    static String bandOf(String ageRange) {
        if (!hasText(ageRange)) {
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
        // 低于 20 岁的输入落到 A1（素材最小档），不返回 null：区间确实给了，只是偏小
        return AGE_BANDS[band];
    }

    /** 取文本里第一个 1~3 位数字（"40-45岁" → 40，"56岁以上" → 56） */
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
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end - start > 3) {
            // 4 位以上数字不是年龄（可能是年份/编号），当作解析失败
            return -1;
        }
        return Integer.parseInt(text.substring(start, end));
    }

    /**
     * L3 按 presetType / 身份关键词猜性别。
     * <p>
     * 设计 §6.1 的口径：manager/executive → 男；frontline → 中性（即不猜，交给 L4）。
     * 身份关键词只认明确指向性别的词（总监/经理/董事长 vs 女士/姐/阿姨），
     * <b>不按角色名猜</b>——设计 §3.4 勘误明确"不能用角色名做任何形象推断"。
     * 都猜不出返回 null。
     */
    static String guessGender(String presetType, String identity) {
        String preset = presetType == null ? "" : presetType.trim().toLowerCase(Locale.ROOT);
        if ("manager".equals(preset) || "executive".equals(preset)) {
            return GENDER_MALE;
        }
        if (!hasText(identity)) {
            return null;
        }
        String text = identity;
        // 女性称谓先判（"董事长女士"这类含男性职务 + 女性称谓时以称谓为准）
        for (String keyword : List.of("女士", "小姐", "太太", "阿姨", "姐")) {
            if (text.contains(keyword)) {
                return GENDER_FEMALE;
            }
        }
        for (String keyword : List.of("总监", "经理", "董事长", "总裁", "先生", "叔", "哥")) {
            if (text.contains(keyword)) {
                return GENDER_MALE;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 供单测核对素材池规模（不改池内容，只暴露只读视图） */
    public static List<String> allAssets() {
        return ALL_ASSETS;
    }

    /** 供单测核对 N=6 这个首期定死的值（与 RandomScriptServiceImpl.candidatesPerBucket 同款口径，不开修改口子） */
    public static int imagesPerGroup() {
        return IMAGES_PER_GROUP;
    }
}
