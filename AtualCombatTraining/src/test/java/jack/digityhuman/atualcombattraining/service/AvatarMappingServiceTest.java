package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService.ActorProfile;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService.AvatarQuery;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService.Level;
import jack.digityhuman.atualcombattraining.service.AvatarMappingService.Resolved;
import jack.digityhuman.atualcombattraining.service.impl.AvatarMappingServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数字人形象定图单测（降级链 L0→L4 + 哈希稳定性，2026-11-05）。
 * <p>
 * 锁住设计里最容易回归的五件事：
 * <ol>
 *   <li><b>L0 上传覆盖优先</b>：管理端传的图覆盖一切自动映射（决策 P4=a）；</li>
 *   <li><b>L1 精确</b>：性别 + 年龄档命中该组合的 6 张池，URL 前缀必须是该性别该档；</li>
 *   <li><b>L2/L3 降级</b>：丢年龄档 → 该性别全档；缺性别 → 按 presetType/身份关键词猜；</li>
 *   <li><b>hash 稳定性</b>：同名同 identity 跨调用恒定（这是"重练沿用同图"的根基）；</li>
 *   <li><b>N 不变时结果不变</b>：N=6 写死，防止将来扩容悄悄把已发布角色全量换脸。</li>
 * </ol>
 * 以及「不造假」：全不命中必须返回 null，绝不拿别的图顶替。
 */
class AvatarMappingServiceTest {

    private final AvatarMappingServiceImpl service = new AvatarMappingServiceImpl();

    private AvatarQuery query(String name, String identity, String gender, String ageRange) {
        return AvatarMappingService.query(
                new ActorProfile(name, identity, gender, ageRange), null, "scenario-1", "random");
    }

    // ---------------------------------------------------------------- L0

    /** L0 上传覆盖：overridden URL 直接透传，且不回落到素材池 */
    @Test
    void L0上传覆盖优先于一切自动映射() {
        AvatarQuery query = AvatarMappingService.query(
                new ActorProfile("王总", "生产总监", "男", "40-45岁"),
                "/avatar/uploaded-by-admin.png", "scenario-1", "manager");

        Resolved resolved = service.resolve(query);

        assertEquals("/avatar/uploaded-by-admin.png", resolved.avatarUrl());
        assertEquals(Level.L0_UPLOAD, resolved.level());
        assertTrue(resolved.present());
    }

    /** L0 覆盖即使属性齐全也不会被 L1 抢走（顺序错会让管理端换图失效） */
    @Test
    void L0覆盖时不再走L1() {
        AvatarQuery query = AvatarMappingService.query(
                new ActorProfile("王总", "生产总监", "男", "40-45岁"),
                "  /avatar/admin.webp  ", "scenario-1", null);

        Resolved resolved = service.resolve(query);

        assertEquals("/avatar/admin.webp", resolved.avatarUrl(), "覆盖值应去空白后原样返回");
        assertEquals(Level.L0_UPLOAD, resolved.level());
    }

    /** 空白覆盖值视同没有：不写空串进快照，继续走自动映射 */
    @Test
    void 空白覆盖值视同无覆盖() {
        AvatarQuery query = AvatarMappingService.query(
                new ActorProfile("王总", "生产总监", "男", "40-45岁"),
                "   ", "scenario-1", null);

        Resolved resolved = service.resolve(query);

        assertEquals(Level.L1_EXACT, resolved.level());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-A3-"));
    }

    // ---------------------------------------------------------------- L1

    /** L1 精确：男 + 40-45岁 → 男 A3 档（37-45）的 6 张池 */
    @Test
    void L1精确命中性别与年龄档() {
        Resolved resolved = service.resolve(query("王总", "生产总监", "男", "40-45岁"));

        assertEquals(Level.L1_EXACT, resolved.level());
        assertNotNull(resolved.avatarUrl());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-A3-"),
                "40-45岁应落在 A3 档，实际：" + resolved.avatarUrl());
    }

    /** 英文性别与中文性别归一化到同一池（模型口径差异不该改变形象） */
    @Test
    void 性别中英文归一化到同一池() {
        Resolved chinese = service.resolve(query("李经理", "采购经理", "女", "30-35岁"));
        Resolved english = service.resolve(query("李经理", "采购经理", "female", "30-35岁"));

        assertEquals(chinese.avatarUrl(), english.avatarUrl());
        assertTrue(chinese.avatarUrl().startsWith("/avatar/f-A2-"));
    }

    /**
     * 区间下界落档：跨档区间取<b>下界</b>（"40-50岁" → 40 → A3，不是 A4）。
     * 同一输入永远同一档，避免"这一版发布落 A3、下一版落 A4"。
     */
    @Test
    void 跨档年龄区间按下界落档且稳定() {
        Resolved wide = service.resolve(query("赵总", "运营总监", "男", "40-50岁"));

        assertTrue(wide.avatarUrl().startsWith("/avatar/m-A3-"),
                "40-50岁下界 40 应落 A3 档，实际：" + wide.avatarUrl());
        // 再算一次，档位与具体图都不变
        assertEquals(wide.avatarUrl(), service.resolve(query("赵总", "运营总监", "男", "40-50岁")).avatarUrl());
    }

    /** 解析出不同档位：22岁 A1 / 33岁 A2 / 50岁 A4 / 60岁 A5 */
    @Test
    void 各年龄档映射正确() {
        assertEquals("/avatar/m-A1-", prefixOf(service.resolve(query("甲", "乙", "男", "22-27岁"))));
        assertEquals("/avatar/m-A2-", prefixOf(service.resolve(query("甲", "乙", "男", "33-36岁"))));
        assertEquals("/avatar/m-A4-", prefixOf(service.resolve(query("甲", "乙", "男", "50-55岁"))));
        assertEquals("/avatar/m-A5-", prefixOf(service.resolve(query("甲", "乙", "男", "60-70岁"))));
    }

    // ---------------------------------------------------------------- L2

    /** L2 丢年龄档：只有性别 → 该性别 5 档共 30 张成池（仍能出图，不掉到 L4） */
    @Test
    void L2缺年龄档退到该性别全档池() {
        Resolved resolved = service.resolve(query("王总", "生产总监", "男", null));

        assertEquals(Level.L2_GENDER_ONLY, resolved.level());
        assertNotNull(resolved.avatarUrl());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-"), "必须是男性素材，实际：" + resolved.avatarUrl());
    }

    /** 年龄文本解析不出来（"中年"）等同缺年龄档 → L2，而不是瞎猜一个档 */
    @Test
    void 年龄文本不可解析时退到L2() {
        Resolved resolved = service.resolve(query("王总", "生产总监", "男", "中年"));

        assertEquals(Level.L2_GENDER_ONLY, resolved.level());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-"));
    }

    /** L2 的池跨 5 个年龄档：连续多个角色名应能落在不同档（证明池不是单一档） */
    @Test
    void L2的池覆盖多个年龄档() {
        Set<String> bands = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            Resolved resolved = service.resolve(query("角色" + i, "身份" + i, "男", null));
            bands.add(bandOfUrl(resolved.avatarUrl()));
        }
        assertTrue(bands.size() >= 3, "30 张池应跨多个年龄档，实际只见到：" + bands);
    }

    // ---------------------------------------------------------------- L3

    /** L3 猜性别：脚本连 gender 都没有，manager 预设 → 男（设计 §6.1 口径） */
    @Test
    void L3按presetType猜性别() {
        Resolved resolved = service.resolve(new AvatarQuery(
                "王总", "负责人", null, null, null, "scenario-1", "manager"));

        assertEquals(Level.L3_GUESSED_GENDER, resolved.level());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-"), "manager 应猜男性");
    }

    /** L3 按身份关键词猜：含"女士"→女；frontline 中性且身份无线索 → 落到 L4 */
    @Test
    void L3按身份关键词猜性别() {
        Resolved female = service.resolve(new AvatarQuery(
                "陈工", "质量部女士", null, null, null, "scenario-1", "frontline"));
        assertEquals(Level.L3_GUESSED_GENDER, female.level());
        assertTrue(female.avatarUrl().startsWith("/avatar/f-"), "含'女士'应猜女性");

        // frontline 是设计中性的预设，身份也无性别线索 → 不猜，直接场景默认图
        Resolved neutral = service.resolve(new AvatarQuery(
                "陈工", "一线客服", null, null, null, "scenario-1", "frontline"));
        assertEquals(Level.L4_SCENE_DEFAULT, neutral.level());
    }

    /** 猜出性别且年龄档已知时，用「猜到的性别 + 该年龄档」的 6 张池 */
    @Test
    void L3猜出性别后仍用已知年龄档() {
        Resolved resolved = service.resolve(new AvatarQuery(
                "王总", "董事长", null, "40-45岁", null, "scenario-1", null));

        assertEquals(Level.L3_GUESSED_GENDER, resolved.level());
        assertTrue(resolved.avatarUrl().startsWith("/avatar/m-A3-"), "应落在男性 A3 档");
    }

    // ---------------------------------------------------------------- L4

    /** L4 场景默认图：同一 scenarioId 恒定（换角色名也还是这张，保证同场景视觉稳定） */
    @Test
    void L4场景默认图同场景恒定() {
        Resolved first = service.resolve(new AvatarQuery(
                "甲", "乙", null, null, null, "scenario-7", null));
        Resolved second = service.resolve(new AvatarQuery(
                "丙", "丁", null, null, null, "scenario-7", null));

        assertEquals(Level.L4_SCENE_DEFAULT, first.level());
        assertEquals(first.avatarUrl(), second.avatarUrl(), "同场景必须恒定映射同一张图");
    }

    /** 不同 scenarioId 通常落到不同图（池够大时不应全撞一张） */
    @Test
    void L4不同场景多数落不同图() {
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            urls.add(service.resolve(new AvatarQuery(
                    null, null, null, null, null, "scenario-" + i, null)).avatarUrl());
        }
        assertTrue(urls.size() >= 10, "20 个场景应至少落到 10 张不同图，实际：" + urls.size());
    }

    // ---------------------------------------------------------------- 兜底

    /** 全不命中（连场景都没有）：返回 null，前端首字占位。绝不拿别的图顶替 */
    @Test
    void 全不命中返回null且不造假() {
        Resolved resolved = service.resolve(new AvatarQuery(null, null, null, null, null, null, null));

        assertEquals(Level.NONE, resolved.level());
        assertNull(resolved.avatarUrl());
        assertFalse(resolved.present());
    }

    /** 性别不可识别且无身份线索：不硬猜成男性，走场景默认图 */
    @Test
    void 性别不可识别不硬猜() {
        Resolved resolved = service.resolve(new AvatarQuery(
                "角色", "某岗位", "未知", null, null, "scenario-3", null));

        assertEquals(Level.L4_SCENE_DEFAULT, resolved.level());
        assertNotNull(resolved.avatarUrl());
    }

    // ---------------------------------------------------------------- 稳定性

    /** hash 稳定性：同名同 identity 跨多次调用恒定（重练沿用同图的根基） */
    @Test
    void hash稳定_同名同身份恒定() {
        String first = service.resolve(query("王总", "服装厂生产总监", "男", "40-45岁")).avatarUrl();
        String second = service.resolve(query("王总", "服装厂生产总监", "男", "40-45岁")).avatarUrl();
        String third = service.resolve(new AvatarQuery(
                "王总", "服装厂生产总监", "男", "40-45岁", null, "scenario-9", null)).avatarUrl();

        assertEquals(first, second);
        assertEquals(first, third, "哈希输入不含场景，换场景必须还是同一张图");
    }

    /** identity 变一个字就换图（设计 §7.2 B2 的已知边界，锁住"确实按 identity 区分"） */
    @Test
    void identity变化会改变定图() {
        Set<String> urls = new HashSet<>();
        for (String identity : List.of("生产总监", "生产总监（兼）", "销售总监", "财务总监", "人事经理")) {
            urls.add(service.resolve(query("王总", identity, "男", "40-45岁")).avatarUrl());
        }
        assertTrue(urls.size() >= 2, "identity 不同应能落到不同图，实际：" + urls);
    }

    /** 同一组合的 6 张池会被不同 hash 均匀用到（不是永远返回第一张） */
    @Test
    void 同组合6张池会被多个角色用到() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            seen.add(service.resolve(query("角色" + i, "身份" + i, "男", "40-45岁")).avatarUrl());
        }
        assertTrue(seen.size() >= 4, "40 个角色应至少用到 4 张不同图，实际只见到：" + seen.size());
        assertTrue(seen.stream().allMatch(u -> u.startsWith("/avatar/m-A3-")),
                "所有命中都必须在该组合池内：" + seen);
    }

    /** N=6 写死：每组合池大小恰好 6，且总素材 60 张（2×5×6） */
    @Test
    void 每组合池恰好6张且总数60() {
        assertEquals(6, AvatarMappingServiceImpl.imagesPerGroup());
        List<String> all = AvatarMappingServiceImpl.allAssets();
        assertEquals(60, all.size());
        assertEquals(60, all.stream().distinct().count(), "素材 URL 不得重复");
        // 每组合 6 张
        for (String genderDir : List.of("m", "f")) {
            for (String band : List.of("A1", "A2", "A3", "A4", "A5")) {
                String prefix = "/avatar/" + genderDir + "-" + band + "-";
                assertEquals(6, all.stream().filter(u -> u.startsWith(prefix)).count(),
                        "组合 " + prefix + " 应恰好 6 张");
            }
        }
    }

    /** 哈希算法必须是 SHA-256（跨进程稳定）：同输入必须得到同一个 4 字节桶 */
    @Test
    void 已发布角色的定图不因进程重启而改变() {
        // 这组断言是"契约值"：换哈希实现（如改用 String.hashCode）会让它们全部失败，
        // 这正是我们要拦住的行为变更（设计 §7.2 B4/B5）
        String url = service.resolve(query("王总", "服装厂生产总监", "男", "40-45岁")).avatarUrl();
        assertEquals(expectedSha256Url("王总|服装厂生产总监", "/avatar/m-A3-"), url);
    }

    // ---------------------------------------------------------------- 读剧本 / 写剧本

    /** 读 drill-script-v1：从 actor 子对象取 name/identity/gender/ageRange */
    @Test
    void 读取drill脚本的演员属性() {
        ObjectNode script = JsonSupport.obj("""
                {"actor":{"name":"王总","identity":"生产总监","gender":"男","ageRange":"40-45岁"}}
                """);

        ActorProfile profile = service.readActor(script);

        assertEquals("王总", profile.name());
        assertEquals("生产总监", profile.identity());
        assertEquals("男", profile.gender());
        assertEquals("40-45岁", profile.ageRange());
    }

    /** 读案例正文（扁平字段）：actorName/actorPosition/actorGender/actorAgeRange */
    @Test
    void 读取案例正文的演员属性() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","actorName":"王总","actorPosition":"生产总监",
                 "actorGender":"男","actorAgeRange":"40-45岁"}
                """);

        ActorProfile profile = service.readActor(payload);

        assertEquals("王总", profile.name());
        assertEquals("生产总监", profile.identity());
        assertEquals("男", profile.gender());
        assertEquals("40-45岁", profile.ageRange());
    }

    /** 写 drill-script-v1：写进 actor.avatarUrl，不动扁平字段 */
    @Test
    void 写入drill脚本的avatarUrl() {
        ObjectNode script = JsonSupport.obj("""
                {"actor":{"name":"王总"}}
                """);

        service.writeAvatarUrl(script, "/avatar/m-A3-01.webp");

        assertEquals("/avatar/m-A3-01.webp", script.path("actor").path("avatarUrl").asText());
        assertFalse(script.has("actorAvatarUrl"), "drill 形态不该写扁平键");
    }

    /** 写案例正文：写扁平 actorAvatarUrl，不动 actor 子对象（本来就没有） */
    @Test
    void 写入案例正文的avatarUrl() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","actorName":"王总"}
                """);

        service.writeAvatarUrl(payload, "/avatar/m-A3-01.webp");

        assertEquals("/avatar/m-A3-01.webp", payload.path("actorAvatarUrl").asText());
    }

    /** null 不写键：避免"定了但为空"的第三种状态，下游据此区分"没定图" */
    @Test
    void null不写空键() {
        ObjectNode payload = JsonSupport.obj("""
                {"scriptName":"高压催单","actorName":"王总"}
                """);

        service.writeAvatarUrl(payload, null);

        assertFalse(payload.has("actorAvatarUrl"));
    }

    // ---------------------------------------------------------------- 辅助

    private String prefixOf(Resolved resolved) {
        String url = resolved.avatarUrl();
        assertNotNull(url);
        int index = url.lastIndexOf('-');
        return url.substring(0, index + 1);
    }

    private String bandOfUrl(String url) {
        assertNotNull(url);
        // /avatar/m-A3-01.webp → A3
        return url.substring("/avatar/m-".length(), "/avatar/m-".length() + 2);
    }

    /** 复算 SHA-256 前 4 字节 % 6，与实现口径独立地对一遍 */
    private String expectedSha256Url(String seed, String prefix) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long value = ((long) (digest[0] & 0xFF) << 24) | ((long) (digest[1] & 0xFF) << 16)
                    | ((long) (digest[2] & 0xFF) << 8) | (long) (digest[3] & 0xFF);
            int index = (int) ((value & 0xFFFFFFFFL) % AvatarMappingServiceImpl.imagesPerGroup());
            return prefix + (index < 9 ? "0" + (index + 1) : String.valueOf(index + 1)) + ".webp";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
