package jack.digityhuman.atualcombattraining.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 对手音色映射单测（2026-11-10）。
 *
 * <p>锁三件事：性别优先于年龄、年龄档只影响女声、没有性别时回退默认音色而不是瞎猜。
 */
class ActorVoiceMappingServiceImplTest {

    private final ActorVoiceMappingServiceImpl mapping = new ActorVoiceMappingServiceImpl();

    @Test
    void 男声_不随年龄段变化() {
        // 男声目前只有龙澄一个可用音色，三个年龄段结果一致
        assertEquals("longcheng_v2", mapping.resolve("男", "25-30岁"));
        assertEquals("longcheng_v2", mapping.resolve("male", "40-45岁"));
        assertEquals("longcheng_v2", mapping.resolve("男", "58-65岁"));
    }

    @Test
    void 女声_按年龄段分三档() {
        // A1/A2 年轻档 → 龙晓晓；A3 → 龙晓春；A4/A5 → 龙婉
        assertEquals("longxiaoxia_v2", mapping.resolve("女", "22-26岁"));
        assertEquals("longxiaoxia_v2", mapping.resolve("女", "30-35岁"));
        assertEquals("longxiaochun_v2", mapping.resolve("女", "40-45岁"));
        assertEquals("longwan_v2", mapping.resolve("女", "48-55岁"));
        assertEquals("longwan_v2", mapping.resolve("女", "60岁以上"));
    }

    @Test
    void 性别缺失_回退默认音色() {
        // 老脚本没有 gender：不能猜，退回默认音色保证有声音
        assertEquals("longwan_v2", mapping.resolve(null, "40-45岁"));
        assertEquals("longwan_v2", mapping.resolve("", null));
        assertEquals("longwan_v2", mapping.resolve("未知", "40岁"));
    }

    @Test
    void 年龄缺失_女声按中年档() {
        // 只有性别没有年龄：取最不突兀的中年档，而不是随机
        assertEquals("longxiaochun_v2", mapping.resolve("女", null));
        assertEquals("longcheng_v2", mapping.resolve("男", null));
    }

    @Test
    void 年龄段取下界落档() {
        // 与形象映射同口径：「40-45」取下界 40 落 A3，不因上界跨档而漂移
        assertEquals("A3", ActorVoiceMappingServiceImpl.bandOf("40-45岁"));
        assertEquals("A5", ActorVoiceMappingServiceImpl.bandOf("56岁以上"));
        assertEquals("A1", ActorVoiceMappingServiceImpl.bandOf("18-22岁"));
        assertNull(ActorVoiceMappingServiceImpl.bandOf("中年"));
    }

    @Test
    void 性别归一化认多种写法() {
        assertEquals("male", ActorVoiceMappingServiceImpl.normalizeGender("男性"));
        assertEquals("male", ActorVoiceMappingServiceImpl.normalizeGender("M"));
        assertEquals("female", ActorVoiceMappingServiceImpl.normalizeGender("女性"));
        assertEquals("female", ActorVoiceMappingServiceImpl.normalizeGender("f"));
        assertNull(ActorVoiceMappingServiceImpl.normalizeGender("不详"));
    }
}
