package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 数字人形象定图服务（预置素材库按角色属性映射，2026-11-05）。
 * <p>
 * <b>设计依据</b>：`tmp/r4-avatar-impl.md` §6（降级链 L0→L4）与 §7（seq 哈希）。
 * <p>
 * <b>为什么要有这一层</b>：形象只挂在「角色（actor）」上，不挂在难度档上（t14 实证）。
 * 同一角色在任何难度档、任何一次重练里都必须得到同一张图，所以定图必须在<b>发布期</b>
 * 算好并写进快照，运行时只读快照、零计算零请求。
 * <p>
 * <b>降级链</b>（优先级从高到低，前一层命中即终止）：
 * <ol>
 *   <li><b>L0 上传覆盖</b>：管理端指定的图（{@code actor_avatar_url} 非空）直接透传，
 *       覆盖任何自动映射结果（用户决策 P4=a：上传优先级最高）；</li>
 *   <li><b>L1 精确</b>：性别 + 年龄档命中 6 张池，用 {@code seq = hash(actorName|identity) % 6} 定图；</li>
 *   <li><b>L2 丢年龄档</b>：只有性别 → 该性别全部 5 个年龄档（30 张）成池，同一 hash 定图；</li>
 *   <li><b>L3 猜性别</b>：连性别都没有 → 按 presetType / 身份关键词猜，再走 L2 的池；</li>
 *   <li><b>L4 场景默认图</b>：全不命中 → 按 scenarioId 固定映射一张（同场景恒定，保证视觉稳定）；</li>
 *   <li><b>兜底</b>：连场景都没有 → 返回 null，前端用「角色名首字圆形」占位（现网已有）。</li>
 * </ol>
 * <p>
 * <b>不造假约束</b>：任何一层都取不到就返回 null，<b>禁止拿别的图顶替</b>（例如用男性图冒充女性）。
 * L4 是设计里明确的一层，不是顶替；它的恒定映射对同一 scenarioId 永不变化。
 * <p>
 * <b>跨进程稳定性</b>：哈希写死 SHA-256，<b>禁用语言内建 hash</b>（§7.2 B4）——
 * Java 的 {@code String.hashCode()} 虽稳定但不可移植，{@code Objects.hash()} 与随机数更不行。
 * 同一角色名+身份必须在不同进程、不同发布批次里得到同一张图，否则「重练沿用」不成立。
 */
public interface AvatarMappingService {

    /** 降级层级：既用于单测断言，也便于线上排查「这张图是怎么来的」 */
    enum Level {
        /** L0 管理端上传覆盖 */
        L0_UPLOAD,
        /** L1 性别 + 年龄档精确命中 */
        L1_EXACT,
        /** L2 丢年龄档（该性别全部年龄档成池） */
        L2_GENDER_ONLY,
        /** L3 猜性别（presetType / 身份关键词）后成池 */
        L3_GUESSED_GENDER,
        /** L4 场景默认图（同一场景恒定） */
        L4_SCENE_DEFAULT,
        /** 全不命中：返回 null，由前端占位 */
        NONE
    }

    /** 定图结果：url 为 null 表示没有可用图（前端占位），level 记录走到哪一层 */
    record Resolved(String avatarUrl, Level level) {

        public boolean present() {
            return avatarUrl != null && !avatarUrl.isBlank();
        }

        public static Resolved none() {
            return new Resolved(null, Level.NONE);
        }
    }

    /** 剧本里读出的演员属性（性别/年龄保持原始文本，规范化在 resolve 内部做） */
    record ActorProfile(String name, String identity, String gender, String ageRange) {

        public static ActorProfile empty() {
            return new ActorProfile(null, null, null, null);
        }
    }

    /**
     * 定图入参。
     * <p>
     * {@code overrideUrl} 来自管理端上传（L0）；{@code gender}/{@code ageRange} 优先取生成脚本里的值，
     * 案例链路还允许用 {@code act_case_script} 的列值补（同源双写，见契约 05 §9.3）。
     */
    record AvatarQuery(String actorName, String identity, String gender, String ageRange,
                       String overrideUrl, String scenarioId, String presetType) {
    }

    /** 用演员属性 + 覆盖/场景/预设类型组一个查询（三个调用点都用它，避免参数顺序写错） */
    static AvatarQuery query(ActorProfile profile, String overrideUrl, String scenarioId,
                             String presetType) {
        ActorProfile safe = profile == null ? ActorProfile.empty() : profile;
        return new AvatarQuery(safe.name(), safe.identity(), safe.gender(), safe.ageRange(),
                overrideUrl, scenarioId, presetType);
    }

    /** 执行降级链，返回命中的图与层级（纯函数，不查库、不发请求） */
    Resolved resolve(AvatarQuery query);

    /**
     * 读剧本里的演员属性：drill-script-v1 读 {@code actor} 子对象，
     * 案例正文（发布快照的扁平字段）读 {@code actorName / actorPosition / actorGender / actorAgeRange}。
     */
    ActorProfile readActor(ObjectNode script);

    /**
     * 把定图结果写回剧本：drill-script-v1 写 {@code actor.avatarUrl}，
     * 案例正文写扁平 {@code actorAvatarUrl}（与既有扁平字段口径一致）。
     * <p>
     * url 为 null 时什么都不写——不造空键，避免下游分不清「没定图」与「定了空图」。
     */
    void writeAvatarUrl(ObjectNode script, String avatarUrl);
}
