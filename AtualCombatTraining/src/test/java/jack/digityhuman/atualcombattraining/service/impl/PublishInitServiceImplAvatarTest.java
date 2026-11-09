package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService;
import jack.digityhuman.atualcombattraining.service.ContentVersionService;
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布期定图回归测试（2026-11-05，`tmp/r4-avatar-impl.md` §3.1/§5.1/§6）。
 * <p>
 * 锁住四件事：
 * <ol>
 *   <li>案例发布：<b>L0 上传覆盖</b>原样透传、并落 act_drill_script.actor_avatar_url 列 + payload 双写；</li>
 *   <li>案例发布：属性够时走 <b>L1</b>，payload 与列是同一个值；属性全缺时留 null，<b>不造假</b>；</li>
 *   <li>随机预生成：脚本 actor 里被写入 avatarUrl（图挂档内角色，不另存档位形象）；</li>
 *   <li>定图结果与 userRole 补字段互不影响（同一个 payload 上两步各自生效）。</li>
 * </ol>
 * 为什么只测包可见方法 `resolveAvatar`：compileOne 是 private 且要连库；
 * 把定图这一步抽成包可见方法后，喂 payload + 案例行即可覆盖全部优先级分支。
 */
class PublishInitServiceImplAvatarTest {

    private PublishInitServiceImpl service;
    private ActDrillScriptMapper drillScriptMapper;

    @BeforeEach
    void setUp() {
        drillScriptMapper = mock(ActDrillScriptMapper.class);
        service = new PublishInitServiceImpl(
                mock(ActThemeMapper.class), mock(ActCaseScriptMapper.class),
                drillScriptMapper, mock(ActScenarioUnitMapper.class),
                mock(ArtifactService.class), mock(ContentVersionService.class),
                mock(RandomScriptService.class), new AvatarMappingServiceImpl(),
                mock(ThreadPoolTaskExecutor.class));
    }

    private ActCaseScript caseRow(String gender, String ageRange, String overrideUrl) {
        ActCaseScript row = new ActCaseScript();
        row.setId("case-1");
        row.setScenarioUnitId("unit-1");
        row.setActorGender(gender);
        row.setActorAgeRange(ageRange);
        row.setActorAvatarUrl(overrideUrl);
        return row;
    }

    /** 案例正文（扁平字段，与 act_drill_script.compiled_payload 同格式） */
    private ObjectNode casePayload(String actorName, String actorPosition,
                                   String gender, String ageRange) {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","actorName":"%s","actorPosition":"%s"}
                """.formatted(actorName, actorPosition));
        if (gender != null) {
            payload.put("actorGender", gender);
        }
        if (ageRange != null) {
            payload.put("actorAgeRange", ageRange);
        }
        return payload;
    }

    // ---------------------------------------------------------------- L0

    /** L0：案例行已有上传图 → 原样透传，且不写自动映射的素材图 */
    @Test
    void 上传覆盖原样透传并忽略属性() {
        ObjectNode payload = casePayload("王总", "生产总监", "男", "40-45岁");
        ActCaseScript row = caseRow("男", "40-45岁", "/avatar/admin-uploaded.png");

        String url = service.resolveAvatar(payload, row);

        assertEquals("/avatar/admin-uploaded.png", url);
        assertEquals("/avatar/admin-uploaded.png", payload.get("actorAvatarUrl").asText(),
                "payload 必须是同一个值（学员端读 payload）");
    }

    /** L0 优先级高于 L1：属性齐全也不该被素材图抢走（否则管理端换图失效） */
    @Test
    void 上传覆盖优先于L1精确匹配() {
        ObjectNode payload = casePayload("王总", "生产总监", "男", "40-45岁");
        ActCaseScript row = caseRow("男", "40-45岁", "/avatar/manual.webp");

        String url = service.resolveAvatar(payload, row);

        assertEquals("/avatar/manual.webp", url);
        assertFalse(url.startsWith("/avatar/m-A3-"), "不应回落到素材池");
    }

    // ---------------------------------------------------------------- L1

    /** L1：属性来自 payload → 命中该组合的 6 张池 */
    @Test
    void 属性齐时走L1并写进payload() {
        ObjectNode payload = casePayload("王总", "生产总监", "男", "40-45岁");
        ActCaseScript row = caseRow(null, null, null);

        String url = service.resolveAvatar(payload, row);

        assertNotNull(url);
        assertTrue(url.startsWith("/avatar/m-A3-"), "40-45岁男性应落 A3 档，实际：" + url);
        assertEquals(url, payload.get("actorAvatarUrl").asText());
    }

    /** 属性在脚本里没有、案例行里有：用列值补（老 artifact + 新确认落库的组合） */
    @Test
    void 脚本缺属性时用案例行列值补() {
        ObjectNode payload = casePayload("王总", "生产总监", null, null);
        ActCaseScript row = caseRow("女", "30-35岁", null);

        String url = service.resolveAvatar(payload, row);

        assertNotNull(url);
        assertTrue(url.startsWith("/avatar/f-A2-"), "应落女性 A2 档，实际：" + url);
    }

    /** 脚本属性优先于列值：两边都有且不一致时以脚本为准（脚本是学员实际看到的内容） */
    @Test
    void 脚本属性优先于案例行列值() {
        ObjectNode payload = casePayload("王总", "生产总监", "男", "40-45岁");
        ActCaseScript row = caseRow("女", "22-27岁", null);

        String url = service.resolveAvatar(payload, row);

        assertTrue(url.startsWith("/avatar/m-A3-"), "脚本说男 A3，列说女 A1，应以脚本为准：" + url);
    }

    /**
     * 属性全缺且身份无性别线索 → 降级到场景默认图（L4），同一场景恒定，且写进 payload。
     * <p>
     * 身份刻意用"负责人"（不含 总监/经理/董事长 等关键词）：含关键词时会先命中 L3 猜性别，
     * 那是设计里更高优先的一层（§6.1），不是本用例要测的路径。
     */
    @Test
    void 属性全缺走L4场景默认图() {
        ObjectNode payload = casePayload("王总", "负责人", null, null);
        ActCaseScript row = caseRow(null, null, null);

        String url = service.resolveAvatar(payload, row);

        assertNotNull(url, "有场景 ID 时 L4 应给出图");
        assertEquals(url, payload.get("actorAvatarUrl").asText());
        // 换角色名/身份再算一次：同场景恒定（L4 按 scenarioId 映射，与角色无关）
        ObjectNode again = casePayload("李总", "对外联系人", null, null);
        assertEquals(url, service.resolveAvatar(again, caseRow(null, null, null)));
    }

    /** 身份含职务关键词 → 命中 L3 猜性别（设计 §6.1），即使没有 scenario 也能出图 */
    @Test
    void 身份含职务关键词走L3猜性别() {
        ObjectNode payload = casePayload("王总", "生产总监", null, null);
        ActCaseScript row = caseRow(null, null, null);
        row.setScenarioUnitId(null);

        String url = service.resolveAvatar(payload, row);

        assertNotNull(url, "L3 猜出男性后无需场景 ID");
        assertTrue(url.startsWith("/avatar/m-"), "总监应猜男性，实际：" + url);
    }

    /** 连场景都没有且猜不出性别（脏数据）→ null，且不在 payload 里造空键 */
    @Test
    void 无场景无属性时返回null且不造空键() {
        ObjectNode payload = casePayload("王总", "负责人", null, null);
        ActCaseScript row = caseRow(null, null, null);
        row.setScenarioUnitId(null);

        String url = service.resolveAvatar(payload, row);

        assertNull(url);
        assertFalse(payload.has("actorAvatarUrl"), "不得写入空键");
    }

    // ---------------------------------------------------------------- 与 userRole 互不影响

    /** 同一次发布：userRole 补字段与定图各自生效，不互相覆盖 */
    @Test
    void userRole补字段与定图互不影响() {
        ObjectNode payload = casePayload("王总", "生产总监", "男", "40-45岁");
        ActCaseScript row = caseRow(null, null, null);
        row.setUserRoleName("陈默");
        row.setUserRolePosition("订单专员");
        row.setUserRoleBackground("负责订单录入");

        service.carryUserRole(payload, row);
        String url = service.resolveAvatar(payload, row);

        assertEquals("陈默", payload.get("userRoleName").asText());
        assertEquals("订单专员", payload.get("userRolePosition").asText());
        assertEquals("负责订单录入", payload.get("userRoleBackground").asText());
        assertEquals(url, payload.get("actorAvatarUrl").asText());
        assertEquals("王总", payload.get("actorName").asText(), "既有字段不得被破坏");
    }

    // ---------------------------------------------------------------- 随机预生成

    /**
     * 随机预生成：脚本里被写入 actor.avatarUrl，且与 act_drill_script 无关
     * （随机脚本身份是 act_random_script，只写 payload，不写 drill 列）。
     */
    @Test
    void 随机预生成把avatarUrl写进actor() {
        ObjectNode script = randomScriptWithAvatar("""
                {"actor":{"name":"王总","identity":"生产总监","gender":"男","ageRange":"40-45岁"}}
                """);

        String url = script.path("actor").path("avatarUrl").asText();
        assertTrue(url.startsWith("/avatar/m-A3-"), "应写进 actor.avatarUrl，实际：" + url);
    }

    /** 随机脚本缺性别/年龄：仍按降级链给出图（不返回空），并写进脚本 */
    @Test
    void 随机脚本缺属性时仍降级出图() {
        ObjectNode script = randomScriptWithAvatar("""
                {"actor":{"name":"王总","identity":"负责人"}}
                """);

        String url = script.path("actor").path("avatarUrl").asText();
        assertFalse(url.isBlank(), "L4 应给出场景默认图");
    }

    /** 定图不改动脚本的其它字段（只增 avatarUrl 一个键） */
    @Test
    void 定图不破坏脚本其它字段() {
        ObjectNode script = randomScriptWithAvatar("""
                {"actor":{"name":"王总","identity":"生产总监","gender":"男","ageRange":"40-45岁",
                 "personality":["务实"]},"goals":{"practiceGoal":"预约下次演示"}}
                """);

        assertEquals("王总", script.path("actor").path("name").asText());
        assertEquals("务实", script.path("actor").path("personality").path(0).asText());
        assertEquals("预约下次演示", script.path("goals").path("practiceGoal").asText());
    }

    /** 用真实的 RandomScriptServiceImpl + 真实定图实现，喂一份脚本并返回被写入后的节点 */
    private ObjectNode randomScriptWithAvatar(String scriptJson) {
        RandomScriptServiceImpl randomService = new RandomScriptServiceImpl(
                mock(jack.digityhuman.atualcombattraining.mapper.ActRandomScriptMapper.class),
                mock(ActDrillScriptMapper.class),
                mock(ActCaseScriptMapper.class),
                mock(ActScenarioUnitMapper.class),
                mock(jack.digityhuman.atualcombattraining.service.ThemeSettingsService.class),
                mock(jack.digityhuman.atualcombattraining.service.AttemptSnapshotService.class),
                mock(jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient.class),
                new AvatarMappingServiceImpl());
        ObjectNode script = JsonSupport.obj(scriptJson);
        randomService.resolveAvatar(script, "unit-1");
        return script;
    }

}
