package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对练提示词渲染冒烟：确认教练和对方两份模板都没有残留变量，关键约束还在。
 * <p>
 * 换 prompt 时最容易犯的两个错：引用了没注入的变量（render 会抛错或留空）、
 * 或者整段改写时把「锚定原话 / 可执行建议 / 字数上限」这些约束删掉。
 * 这个测试锁住后者，前者由 DrillTaskTest 的注入用例覆盖。
 */
class PromptRenderSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COACH = "prompts/workflows/drill-chat/coach.md";
    private static final String ACTOR = "prompts/workflows/drill-chat/actor.md";

    /** 只截获系统提示词，模型输出不重要 */
    private static LlmClient capture(Object[] holder) {
        return request -> {
            holder[0] = String.valueOf(holder[0]) + request.messages().get(0).content();
            String system = request.messages().get(0).content();
            String body = system.contains("场边教练")
                    ? "{\"coachReview\":\"先复述顾虑\",\"targetProgress\":0,\"deltaReason\":\"d\",\"behaviorHits\":[]}"
                    : "{\"reply\":\"请讲\"}";
            return new LlmResponse(body, List.of(), null);
        };
    }

    private static WorkflowContext context() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("study_mode", "1");
        inputs.put("query", "王总您好");
        inputs.put("recent_messages_json", "[]");
        inputs.put("progress_json", "{}");
        inputs.put("script_json", "{\"actor\":{\"name\":\"王总\",\"identity\":\"生产总监\","
                + "\"personality\":[\"务实\",\"多疑\"]},\"goals\":{\"behaviorGuidance\":[\"先复述客户顾虑\"]}}");
        return new WorkflowContext(inputs, MAPPER);
    }

    @Test
    void 渲染后无残留模板变量() {
        Object[] holder = new Object[] {""};
        new DrillChatTurn(new PromptProvider(), MAPPER).turn(context(), capture(holder));
        String system = String.valueOf(holder[0]);

        assertFalse(system.contains("{{#"), "系统提示词不应残留未替换的模板变量");
        // 派生为空的相关段落不应留空值占位
        assertTrue(system.contains("王总"), "角色画像应已代入");
        assertTrue(system.contains("先复述客户顾虑"), "行为指引应已代入");
    }

    @Test
    void 点评四要素在教练提示词里() {
        String raw = new PromptProvider().load(COACH);

        // 1. 锚定学员原话
        assertTrue(raw.contains("锚定学员本轮原话"), "缺少「锚定原话」约束");
        // 2. 结合角色性格 / 行为指引
        assertTrue(raw.contains("actor_profile"), "未引用角色画像变量");
        assertTrue(raw.contains("behavior_guidance"), "未引用行为指引变量");
        // 3. 可执行下一步
        assertTrue(raw.contains("可执行的下一步"), "缺少「可执行建议」约束");
        // 4. 字数与句数上限
        assertTrue(raw.contains("100 字内"), "缺少字数上限");
        assertTrue(raw.contains("最多两句"), "缺少句数上限");
        // 反空泛自检
        assertTrue(raw.contains("仍然成立"), "缺少「换一句原话仍成立则说明没锚定」的自检");
    }

    @Test
    void 考核模式由代码跳过教练_对方提示词禁止夹带提示() {
        String raw = new PromptProvider().load(ACTOR);
        assertTrue(raw.contains("禁止在台词里夹带提示"), "考核模式的对方提示词不能夹带提示");
        assertFalse(raw.contains("coachReview"), "对方提示词不应再产出教练点评");
    }
}
