package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.examservice.dto.req.PointDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.impl.PointServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-01：考核点增删后必须重算 exam_lib.point_qty。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PointServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamPoint.class);
        TableInfoHelper.initTableInfo(assistant, ExamLib.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestionPoint.class);
    }

    @Mock private ExamPointMapper pointMapper;
    @Mock private ExamLibMapper libMapper;
    @Mock private ExamQuestionPointMapper questionPointMapper;
    @InjectMocks private PointServiceImpl service;

    @Test
    void 批量保存后重算题库考核点数() {
        ExamLib lib = new ExamLib();
        lib.setId("L1");
        lib.setPointQty(1);
        when(libMapper.selectById("L1")).thenReturn(lib);
        when(pointMapper.selectList(any())).thenReturn(List.of());
        when(pointMapper.selectCount(any())).thenReturn(2L);

        PointDtos.BatchReq.Item item = new PointDtos.BatchReq.Item();
        item.setName("新增考点");
        PointDtos.BatchReq req = new PointDtos.BatchReq();
        req.setLibId("L1");
        req.setPoints(List.of(item));

        service.batchSave(req);

        assertThat(lib.getPointQty()).isEqualTo(2);
        verify(libMapper).updateById(lib);
    }

    /**
     * B-02：删除考点时清理关联；没有其他考点的试题落回默认考点。
     */
    @Test
    void 删除考点时清理关联并把空关联试题落回默认考点() {
        ExamPoint def = new ExamPoint();
        def.setId("D");
        def.setLibId("L1");
        def.setName("默认考点");
        def.setDefaultPoint(1);
        ExamPoint custom = new ExamPoint();
        custom.setId("P");
        custom.setLibId("L1");
        custom.setName("自定义考点");
        custom.setDefaultPoint(0);
        when(pointMapper.selectList(any())).thenReturn(List.of(def, custom));

        ExamQuestionPoint r1 = new ExamQuestionPoint();
        r1.setQuesId("Q1");
        r1.setPointId("P");
        ExamQuestionPoint r2 = new ExamQuestionPoint();
        r2.setQuesId("Q2");
        r2.setPointId("P");
        when(questionPointMapper.selectList(any())).thenReturn(List.of(r1, r2));
        // Q1 已无其他考点 → 落回默认；Q2 还有其他考点 → 不动
        when(questionPointMapper.selectCount(any())).thenReturn(0L, 1L);

        ExamLib lib = new ExamLib();
        lib.setId("L1");
        when(libMapper.selectById("L1")).thenReturn(lib);
        when(pointMapper.selectCount(any())).thenReturn(1L);

        PointDtos.BatchReq.Item keepDef = new PointDtos.BatchReq.Item();
        keepDef.setId("D");
        keepDef.setName("默认考点");
        PointDtos.BatchReq req = new PointDtos.BatchReq();
        req.setLibId("L1");
        req.setPoints(List.of(keepDef));

        service.batchSave(req);

        verify(questionPointMapper).delete(any());
        ArgumentCaptor<ExamQuestionPoint> captor = ArgumentCaptor.forClass(ExamQuestionPoint.class);
        verify(questionPointMapper).insert(captor.capture());
        assertThat(captor.getValue().getQuesId()).isEqualTo("Q1");
        assertThat(captor.getValue().getPointId()).isEqualTo("D");
        verify(pointMapper).deleteById("P");
    }
}
