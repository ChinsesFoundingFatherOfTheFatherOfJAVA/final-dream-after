package jack.digityhuman.atualcombattraining.service;

/**
 * 对练对手音色映射（2026-11-10）。
 *
 * <p>角色音色要跟着形象一起匹配「性别 × 年龄段」，否则男客户用女声说话会明显出戏。
 * 实现见 {@code ActorVoiceMappingServiceImpl}。
 */
public interface ActorVoiceMappingService {

    /**
     * 按角色属性选 TTS 音色。
     *
     * @param gender   脚本里的 actor.gender（男/女/male/female…，认不出来视为未知）
     * @param ageRange 脚本里的 actor.ageRange（如「40-45岁」；解析不出按中年档处理）
     * @return CosyVoice 音色值；性别未知时返回默认音色，不抛异常
     */
    String resolve(String gender, String ageRange);
}
