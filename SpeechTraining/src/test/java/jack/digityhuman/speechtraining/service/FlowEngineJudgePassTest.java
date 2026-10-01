package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.KeyPointsUtil.KeyPoint;
import jack.digityhuman.speechtraining.entity.TrainingNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流转条件复核（judgePass）单测：后端不信任 LLM 自评，命中匹配只认"命中项包含要点全文"。
 * <p>被砍掉的是反方向包含（要点包含命中项）——它让 LLM 幻觉出的一个词、甚至半个词就能顶上一条要点。
 */
class FlowEngineJudgePassTest {

    /** judgePass 只依赖入参，不触达任何协作者，故依赖全部传 null */
    private final FlowEngineServiceImpl service = new FlowEngineServiceImpl(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    private static List<KeyPoint> points(String... contents) {
        List<KeyPoint> list = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            list.add(new KeyPoint(String.valueOf(i + 1), contents[i], i + 1));
        }
        return list;
    }

    private static TrainingNode anyN(int n) {
        TrainingNode node = new TrainingNode();
        node.setId(10L);
        node.setPassConditionType(1);
        node.setPassConditionN(n);
        return node;
    }

    private static TrainingNode specified(String pointIdsJson) {
        TrainingNode node = new TrainingNode();
        node.setId(10L);
        node.setPassConditionType(2);
        node.setPassConditionPoints(pointIdsJson);
        return node;
    }

    @Test
    void 命中项逐字等于要点_算命中() {
        assertTrue(service.judgePass(anyN(1), points("我明天上午十点去拜访客户"), List.of("我明天上午十点去拜访客户")));
    }

    @Test
    void 命中项只是要点的前半句_不算命中() {
        // 旧实现按"要点包含命中项"放行：LLM 回一个短语就能顶掉整条长要点
        assertFalse(service.judgePass(anyN(1), points("我明天上午十点去拜访客户"), List.of("我明天上午")));
    }

    @Test
    void 单字幻觉命中项_不算命中() {
        assertFalse(service.judgePass(anyN(1), points("客户名称"), List.of("称")));
    }

    @Test
    void 命中项包含要点全文_算命中() {
        assertTrue(service.judgePass(anyN(1), points("报价"), List.of("请您给个报价参考")));
    }

    @Test
    void 要点内容重复时按条计数_不被合并成一条() {
        // 配置里就是两条要点，"命中任意 2 个"就该按两条算；旧实现用 Set 按内容去重会永远凑不满
        assertTrue(service.judgePass(anyN(2), points("报价", "报价"), List.of("报价")));
    }

    @Test
    void 命中数量不足_不通过() {
        assertFalse(service.judgePass(anyN(2), points("报价", "账期", "售后"), List.of("报价")));
    }

    @Test
    void 指定要点全部命中才通过() {
        List<KeyPoint> kps = points("报价", "账期", "售后");
        assertTrue(service.judgePass(specified("[\"1\",\"3\"]"), kps, List.of("报价", "售后")));
        assertFalse(service.judgePass(specified("[\"1\",\"3\"]"), kps, List.of("报价")));
    }

    @Test
    void 指定要点解析不到内容时退化为命中任意一个() {
        List<KeyPoint> kps = points("报价", "账期");
        assertTrue(service.judgePass(specified("[\"99\"]"), kps, List.of("报价")));
        assertFalse(service.judgePass(specified("[\"99\"]"), kps, List.of()));
    }

    @Test
    void 空格差异不影响命中() {
        assertTrue(service.judgePass(anyN(1), points("上 午 十 点"), List.of("上午十点见面")));
    }

    @Test
    void 未配置要点的历史节点_按无门槛放行() {
        // 发布校验已拦住新配置，这里放行只是为了不让存量项目卡死学员
        assertTrue(service.judgePass(anyN(1), List.of(), List.of()));
    }
}
