package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.examservice.dto.req.PointDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.service.impl.PointServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    }

    @Mock private ExamPointMapper pointMapper;
    @Mock private ExamLibMapper libMapper;
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
}
