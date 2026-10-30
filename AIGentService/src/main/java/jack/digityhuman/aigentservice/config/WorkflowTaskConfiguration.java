package jack.digityhuman.aigentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import jack.digityhuman.aigentservice.workflow.CaseGenTask;
import jack.digityhuman.aigentservice.workflow.DrillChatTurn;
import jack.digityhuman.aigentservice.workflow.DrillReportTask;
import jack.digityhuman.aigentservice.workflow.DrillScriptTask;
import jack.digityhuman.aigentservice.workflow.DrillSuggestTask;
import jack.digityhuman.aigentservice.workflow.ExamGenTask;
import jack.digityhuman.aigentservice.workflow.ProductProfileGenTask;
import jack.digityhuman.aigentservice.workflow.AvatarGenTask;
import jack.digityhuman.aigentservice.workflow.ImageGenerationClient;
import jack.digityhuman.aigentservice.workflow.ReportScoreTask;
import jack.digityhuman.aigentservice.workflow.SingleStructuredTask;
import jack.digityhuman.aigentservice.workflow.SpeechScoreTask;
import jack.digityhuman.aigentservice.workflow.ThemeGenTask;
import jack.digityhuman.aigentservice.workflow.WorkflowEngine;
import jack.digityhuman.aigentservice.workflow.WorkflowTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** P2/P3 workflow 任务装配。 */
@Configuration
public class WorkflowTaskConfiguration {

    @Bean
    public WorkflowTask themeGenTask(PromptProvider prompts, ObjectMapper mapper) {
        return new ThemeGenTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask caseGenTask(PromptProvider prompts, ObjectMapper mapper) {
        return new CaseGenTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask genSceneTask(PromptProvider prompts, ObjectMapper mapper) {
        return new SingleStructuredTask("gen-scene", "sceneResult",
                "prompts/workflows/gen-scene/scene.md",
                "prompts/workflows/gen-scene/scene.schema.json", prompts, mapper);
    }

    @Bean
    public WorkflowTask genOutlineTask(PromptProvider prompts, ObjectMapper mapper) {
        return new SingleStructuredTask("gen-outline", "outlineResult",
                "prompts/workflows/gen-outline/outline.md",
                "prompts/workflows/gen-outline/outline.schema.json", prompts, mapper);
    }

    @Bean
    public WorkflowTask genScriptTask(PromptProvider prompts, ObjectMapper mapper) {
        return new SingleStructuredTask("gen-script", "scriptResult",
                "prompts/workflows/gen-script/script.md",
                "prompts/workflows/gen-script/script.schema.json", prompts, mapper);
    }

    @Bean
    public WorkflowTask speechScoreTask(PromptProvider prompts, ObjectMapper mapper) {
        return new SpeechScoreTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask reportScoreTask(PromptProvider prompts, ObjectMapper mapper) {
        return new ReportScoreTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask examGenTask(PromptProvider prompts, ObjectMapper mapper) {
        return new ExamGenTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask drillScriptTask(PromptProvider prompts, ObjectMapper mapper) {
        // 学员端随机/自定义练习脚本（契约 08 AG-D01）
        return new DrillScriptTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask drillReportTask(PromptProvider prompts, ObjectMapper mapper) {
        // 训练/考核结构化报告（契约 08 AG-D04）
        return new DrillReportTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask drillSuggestTask(PromptProvider prompts, ObjectMapper mapper) {
        // 训练模式建议（契约 08 AG-D03）
        return new DrillSuggestTask(prompts, mapper);
    }

    @Bean
    public WorkflowTask productProfileGenTask(PromptProvider prompts, ObjectMapper mapper) {
        // 管理端产品档案候选生成（契约 09 §5.2；候选只返回不落库）
        return new ProductProfileGenTask(prompts, mapper);
    }

    @Bean
    public DrillChatTurn drillChatTurn(PromptProvider prompts, ObjectMapper mapper) {
        // 对练一轮生成（契约 08 AG-D02 的模型部分；SSE 由 InternalDrillController 编排）
        return new DrillChatTurn(prompts, mapper);
    }

    @Bean
    public WorkflowTask avatarGenTask(PromptProvider prompts, ObjectMapper mapper,
                                      ImageGenerationClient imageClient) {
        return new AvatarGenTask(prompts, mapper, imageClient);
    }

    @Bean
    public WorkflowEngine workflowEngine(List<WorkflowTask> tasks, LlmClient llm,
                                         ObjectMapper mapper) {
        return new WorkflowEngine(tasks, llm, mapper);
    }
}
