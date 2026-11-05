package jack.digityhuman.atualcombattraining.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAntiCheatEvent;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAttemptEvent;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActCustomerProfile;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActExamQuotaAdjustment;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActReportDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActStudentScenarioStat;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * MyBatis-Plus 单测支撑。
 * <p>
 * 纯 Mockito 单测里构造 {@code LambdaQueryWrapper/LambdaUpdateWrapper}（如 {@code ActTheme::getId}）
 * 需要该实体的 TableInfo 已注册，否则抛 "can not find lambda cache for this entity"。
 * Spring 上下文启动会顺带注册，导致"单跑某个测试类失败、跑全量却通过"的顺序依赖——
 * 这里显式注册，让单测可独立运行。
 */
public final class MybatisTestSupport {

    private MybatisTestSupport() {
    }

    /** 注册本模块涉及的全部实体（测试类 @BeforeAll 调用一次即可） */
    public static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        for (Class<?> entity : new Class<?>[]{
                ActAiArtifact.class, ActAntiCheatEvent.class, ActAiMaterial.class, ActAttemptEvent.class, ActAttemptMessage.class,
                ActCaseScript.class, ActContentVersionMaterial.class, ActCustomerProfile.class,
                ActDrillScript.class, ActEvaluationDimension.class, ActExamQuotaAdjustment.class,
                ActPracticeAttempt.class, ActPracticeReport.class,
                ActProductProfile.class, ActReportDimension.class,
                ActScenarioProductProfile.class, ActScenarioUnit.class,
                ActStudentScenarioStat.class, ActTheme.class, ActThemeParticipant.class,
                ActThemeSettings.class, ActThemeVersion.class}) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}

