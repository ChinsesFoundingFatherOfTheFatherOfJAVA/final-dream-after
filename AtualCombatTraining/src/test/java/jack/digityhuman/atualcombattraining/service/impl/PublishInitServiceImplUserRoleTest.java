package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ContentVersionService;
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 发布快照的 userRole 补字段单测（2026-11-05）。
 * <p>
 * 锁两件事：
 * <ol>
 *   <li>artifact 原 JSON 已带 userRole 时原样保留（不被案例行的空列清掉）；</li>
 *   <li>artifact 没有、案例行有（老 artifact + 新确认落库）时用列值补进 payload；</li>
 *   <li>两边都没有时不写入任何键（不放空串，<b>也不拿 scriptBackground 顶替</b>）。</li>
 * </ol>
 * 为什么不用 mock 版 compileOne：它是 private 且要连库；把补字段这一步独立成
 * 包可见方法后，这里直接喂 payload 就能覆盖全部分支，不需要伪造整条发布链路。
 */
class PublishInitServiceImplUserRoleTest {

    private PublishInitServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PublishInitServiceImpl(
                mock(ActThemeMapper.class), mock(ActCaseScriptMapper.class),
                mock(ActDrillScriptMapper.class), mock(ActScenarioUnitMapper.class),
                mock(ArtifactService.class), mock(ContentVersionService.class),
                mock(RandomScriptService.class), new AvatarMappingServiceImpl(),
                mock(ThreadPoolTaskExecutor.class));
    }

    private ActCaseScript caseRow(String name, String position, String background) {
        ActCaseScript row = new ActCaseScript();
        row.setUserRoleName(name);
        row.setUserRolePosition(position);
        row.setUserRoleBackground(background);
        return row;
    }

    /** artifact 原 JSON 已有 userRole：不得被覆盖 */
    @Test
    void 快照已有userRole时原样保留() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","scriptBackground":"场景背景",
                 "userRoleName":"陈默","userRolePosition":"订单专员","userRoleBackground":"负责订单录入"}
                """);
        ActCaseScript row = caseRow("另一个名字", "另一个岗位", "另一个背景");

        service.carryUserRole(payload, row);

        assertEquals("陈默", payload.get("userRoleName").asText());
        assertEquals("订单专员", payload.get("userRolePosition").asText());
        assertEquals("负责订单录入", payload.get("userRoleBackground").asText());
    }

    /** artifact 没有（老 v1 案例）、案例行有：用列值补进快照，否则发布后前端读不到 */
    @Test
    void 快照缺失userRole时用案例行列值补入() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","scriptBackground":"场景背景"}
                """);
        ActCaseScript row = caseRow("陈默", "订单专员", "负责订单录入");

        service.carryUserRole(payload, row);

        assertEquals("陈默", payload.get("userRoleName").asText());
        assertEquals("订单专员", payload.get("userRolePosition").asText());
        assertEquals("负责订单录入", payload.get("userRoleBackground").asText());
        // 原有字段不被破坏
        assertEquals("场景背景", payload.get("scriptBackground").asText());
    }

    /** 两边都没有：不写任何键，绝不拿 scriptBackground 顶 userRoleBackground */
    @Test
    void 两边都没有时不留空键也不顶替背景() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","scriptBackground":"场景背景"}
                """);
        ActCaseScript row = caseRow(null, null, null);

        service.carryUserRole(payload, row);

        assertFalse(payload.has("userRoleName"), "不得写入空键");
        assertFalse(payload.has("userRolePosition"));
        assertFalse(payload.has("userRoleBackground"), "不得拿 scriptBackground 顶替学员背景");
        // 场景背景仍在原位，没被搬去 userRoleBackground
        assertEquals("场景背景", payload.get("scriptBackground").asText());
    }

    /** 空串列值视同缺值：不把空串写进快照（否则下游分不清"没生成"和"生成空"） */
    @Test
    void 空串列值不写入快照() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","scriptBackground":"场景背景"}
                """);
        ActCaseScript row = caseRow("", "  ", "");

        service.carryUserRole(payload, row);

        assertNull(payload.get("userRoleName"));
        assertNull(payload.get("userRolePosition"));
        assertNull(payload.get("userRoleBackground"));
    }
}
