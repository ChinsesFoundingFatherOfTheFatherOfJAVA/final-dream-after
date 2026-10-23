package jack.digityhuman.aigentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import jack.digityhuman.aigentservice.workflow.CaseGenTask;
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
    public WorkflowEngine workflowEngine(List<WorkflowTask> tasks, LlmClient llm,
                                         ObjectMapper mapper) {
        return new WorkflowEngine(tasks, llm, mapper);
    }
}
