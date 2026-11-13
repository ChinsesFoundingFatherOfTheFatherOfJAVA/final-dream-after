package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeSettingDtos;
import jack.digityhuman.examservice.entity.ExamUserSetting;
import jack.digityhuman.examservice.mapper.ExamUserSettingMapper;
import jack.digityhuman.examservice.service.impl.ArrangeSettingServiceImpl;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-47 列设置规则：account / fullname / operation 三列不可隐藏；order 一律从 1 重排。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangeSettingServiceTest {

    @Mock
    private ExamUserSettingMapper settingMapper;
    @Mock
    private CurrentUserService currentUserService;
    @InjectMocks
    private ArrangeSettingServiceImpl service;

    private ArrangeSettingDtos.Column col(String key, boolean visible, int order) {
        ArrangeSettingDtos.Column c = new ArrangeSettingDtos.Column();
        c.setKey(key);
        c.setVisible(visible);
        c.setOrder(order);
        return c;
    }

    private ArrangeSettingDtos.ColumnSetting req(ArrangeSettingDtos.Column... cols) {
        ArrangeSettingDtos.ColumnSetting s = new ArrangeSettingDtos.ColumnSetting();
        s.setColumns(List.of(cols));
        return s;
    }

    @Test
    void 显式隐藏保留列会被拒绝() {
        when(currentUserService.userId(null)).thenReturn("13");
        assertThatThrownBy(() -> service.saveStudentColumns(
                req(col("account", false, 1), col("fullname", true, 2), col("operation", true, 3))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可隐藏");
    }

    /** 清单里没带保留列时，后端补齐并强制可见（而不是报错）。 */
    @Test
    void 缺失的保留列会被补齐且可见() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(settingMapper.selectOne(any())).thenReturn(null);
        service.saveStudentColumns(req(col("account", true, 1), col("fullname", true, 2)));

        ArgumentCaptor<ExamUserSetting> captor = ArgumentCaptor.forClass(ExamUserSetting.class);
        verify(settingMapper).insert(captor.capture());
        String json = captor.getValue().getSettingJson();
        assertThat(json).contains("operation").contains("account").contains("fullname");
    }

    @Test
    void 保存时保留列强制可见且order从1重排() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(settingMapper.selectOne(any())).thenReturn(null);
        service.saveStudentColumns(req(
                col("fullname", true, 9), col("account", true, 7), col("deptName", false, 3)));

        ArgumentCaptor<ExamUserSetting> captor = ArgumentCaptor.forClass(ExamUserSetting.class);
        verify(settingMapper).insert(captor.capture());
        String json = captor.getValue().getSettingJson();
        assertThat(json).contains("deptName");
        // 保留列必须可见；order 从 1 连续
        assertThat(json).contains("\"key\":\"account\",\"visible\":true")
                .contains("\"key\":\"fullname\",\"visible\":true")
                .contains("\"key\":\"operation\",\"visible\":true")
                .contains("\"order\":1").contains("\"order\":2").contains("\"order\":3").contains("\"order\":4");
    }

    @Test
    void 默认列设置含26列且保留列可见() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(settingMapper.selectOne(any())).thenReturn(null);
        ArrangeSettingDtos.ColumnSetting setting = service.getStudentColumns();
        // 与原平台「列设置」面板对齐（26 项）；少一项就说明 DEFAULT_ORDER 又漂了
        assertThat(setting.getColumns()).hasSize(26);
        assertThat(setting.getColumns().stream()
                .filter(c -> List.of("account", "fullname", "operation").contains(c.getKey()))
                .allMatch(ArrangeSettingDtos.Column::getVisible)).isTrue();
    }
}
