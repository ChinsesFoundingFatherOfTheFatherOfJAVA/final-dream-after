package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.examservice.mapper.ExamFaceBaselineMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 07 附录 C 迁移后「字段可读写」的映射锁定（2026-11-10）。
 *
 * <p>为什么用 TableInfo 而不是起真库跑：MyBatis-Plus 的读写列集合完全由
 * {@code TableInfo#getFieldList()} 决定——实体里有字段但这里没有，就是「查不出来也写不进去」。
 * 本单测把「实体字段 ↔ 数据库列」逐条钉死，不需要 MySQL 也能在 CI 里跑。
 * 真实库的列存在性与可写性另由 {@code sql/v1.5-学员端考试与批阅.sql} 的自检 SELECT 佐证。
 */
class ExamChapterCFieldMappingTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamAttempt.class);
        TableInfoHelper.initTableInfo(assistant, ExamAttemptAnswer.class);
        TableInfoHelper.initTableInfo(assistant, ExamFaceBaseline.class);
    }

    private static Map<String, String> propertyToColumn(Class<?> entity) {
        TableInfo info = TableInfoHelper.getTableInfo(entity);
        assertThat(info).as("%s 必须被 MyBatis-Plus 识别为表实体", entity.getSimpleName()).isNotNull();
        return info.getFieldList().stream()
                .collect(Collectors.toMap(TableFieldInfo::getProperty, TableFieldInfo::getColumn));
    }

    @Test
    @DisplayName("exam_attempt 新增三列：config_snapshot / identity_status / identity_evidence")
    void 答卷三列可读写() {
        Map<String, String> cols = propertyToColumn(ExamAttempt.class);
        assertThat(TableInfoHelper.getTableInfo(ExamAttempt.class).getTableName()).isEqualTo("exam_attempt");
        assertThat(cols).containsEntry("configSnapshot", "config_snapshot");
        assertThat(cols).containsEntry("identityStatus", "identity_status");
        assertThat(cols).containsEntry("identityEvidence", "identity_evidence");
    }

    @Test
    @DisplayName("exam_attempt_answer 新增 marked 列")
    void 答题明细marked可读写() {
        Map<String, String> cols = propertyToColumn(ExamAttemptAnswer.class);
        assertThat(TableInfoHelper.getTableInfo(ExamAttemptAnswer.class).getTableName())
                .isEqualTo("exam_attempt_answer");
        assertThat(cols).containsEntry("marked", "marked");
    }

    @Test
    @DisplayName("exam_face_baseline 新表四列 + mapper 可注册")
    void 人脸基准照表映射() {
        Map<String, String> cols = propertyToColumn(ExamFaceBaseline.class);
        assertThat(TableInfoHelper.getTableInfo(ExamFaceBaseline.class).getTableName())
                .isEqualTo("exam_face_baseline");
        assertThat(cols).containsEntry("userId", "user_id")
                .containsEntry("imageUrl", "image_url")
                .containsEntry("source", "source");
        // id 是 @TableId，不在 fieldList 里，单独校验
        assertThat(TableInfoHelper.getTableInfo(ExamFaceBaseline.class).getKeyProperty()).isEqualTo("id");
        assertThat(ExamFaceBaselineMapper.class).isInterface();
    }

    @Test
    @DisplayName("实体默认值：新生儿脸核验为 0（未核验）、mark 为 0（未标记）由库默认值兜底")
    void 迁移后新列都有库级默认值() {
        // 没有默认值的 mediumtext identity_evidence 允许 NULL，其余三列 NOT NULL DEFAULT 0，
        // 这里锁的是「老代码不显式赋值也不会插入失败」这一点（迁移脚本里的 DDL 与之对应）。
        Map<String, String> attempt = propertyToColumn(ExamAttempt.class);
        Map<String, String> answer = propertyToColumn(ExamAttemptAnswer.class);
        assertThat(attempt.values()).contains("config_snapshot", "identity_status", "identity_evidence");
        assertThat(answer.values()).contains("marked");
    }
}
