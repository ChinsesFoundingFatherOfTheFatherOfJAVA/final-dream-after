package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeNoticeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeNoticeRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamChangeNotice;
import jack.digityhuman.examservice.entity.ExamNoticeOutbox;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamChangeNoticeMapper;
import jack.digityhuman.examservice.mapper.ExamNoticeOutboxMapper;
import jack.digityhuman.examservice.service.impl.ArrangeNoticeServiceImpl;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.NotificationPublishService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 变更通知（API-41/42/43）：默认标题/正文按变更字段生成；发送写 {发送记录 + outbox}，
 * dedupKey 唯一；考试不存在抛业务异常。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangeNoticeServiceTest {

    /**
     * 纯单测没有 Spring 容器，MyBatis-Plus 实体元信息不会被 mapper 扫描初始化，
     * 而 {@code LambdaUpdateWrapper.set(...)} 会立即解析列名，缺元信息直接抛异常。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamNoticeOutbox.class);
        TableInfoHelper.initTableInfo(assistant, ExamChangeNotice.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrange.class);
    }

    @Mock
    private ExamArrangeMapper arrangeMapper;
    @Mock
    private ExamArrangeStudentMapper studentMapper;
    @Mock
    private ExamChangeNoticeMapper noticeMapper;
    @Mock
    private ExamNoticeOutboxMapper outboxMapper;
    @Mock
    private ExamArrangeOperationLogMapper logMapper;
    @Mock
    private OrgQueryService orgQueryService;
    @Mock
    private NotificationPublishService notificationPublishService;
    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private ArrangeNoticeServiceImpl service;

    private ExamArrange arrange(String id, String name) {
        ExamArrange a = new ExamArrange();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private ArrangeNoticeDtos.NoticeReq req(String title, String content, String... fields) {
        ArrangeNoticeDtos.NoticeReq r = new ArrangeNoticeDtos.NoticeReq();
        r.setTitle(title);
        r.setContent(content);
        r.setChangedFields(List.of(fields));
        return r;
    }

    @Test
    void 预览按变更字段生成默认标题与正文() {
        when(arrangeMapper.selectById("A1")).thenReturn(arrange("A1", "R系列机器人考试"));
        when(studentMapper.selectCount(any())).thenReturn(3L);

        ArrangeNoticeRespDtos.Preview p = service.preview("A1", req("", "", "entryStartAt", "entryEndAt"));

        assertThat(p.getTitle()).isEqualTo("考试信息变更通知");
        assertThat(p.getContent()).contains("R系列机器人考试").contains("入场开始时间").contains("入场结束时间");
        assertThat(p.getRecipientQty()).isEqualTo(3);
    }

    @Test
    void 预览保留用户自定义标题与正文() {
        when(arrangeMapper.selectById("A1")).thenReturn(arrange("A1", "考试"));
        when(studentMapper.selectCount(any())).thenReturn(0L);

        ArrangeNoticeRespDtos.Preview p = service.preview("A1", req("调整通知", "请重新确认时间", "entryStartAt"));

        assertThat(p.getTitle()).isEqualTo("调整通知");
        assertThat(p.getContent()).isEqualTo("请重新确认时间");
    }

    @Test
    void 发送写入发送记录与outbox且状态待投递() {
        when(arrangeMapper.selectById("A1")).thenReturn(arrange("A1", "考试"));
        when(studentMapper.selectCount(any())).thenReturn(5L);
        when(currentUserService.userId(null)).thenReturn("13");

        ArrangeNoticeRespDtos.SendResult r = service.send("A1", req("变更", "内容", "entryStartAt"));

        assertThat(r.getNoticeId()).isNotBlank();
        assertThat(r.getEventId()).isNotBlank();
        assertThat(r.getRecipientQty()).isEqualTo(5);

        ArgumentCaptor<ExamChangeNotice> nc = ArgumentCaptor.forClass(ExamChangeNotice.class);
        verify(noticeMapper).insert(nc.capture());
        assertThat(nc.getValue().getStatus()).isZero();
        assertThat(nc.getValue().getRecipientQty()).isEqualTo(5);
        assertThat(nc.getValue().getChangedFields()).isEqualTo("[\"entryStartAt\"]");

        ArgumentCaptor<ExamNoticeOutbox> oc = ArgumentCaptor.forClass(ExamNoticeOutbox.class);
        verify(outboxMapper).insert(oc.capture());
        assertThat(oc.getValue().getStatus()).isZero();
        assertThat(oc.getValue().getEventCode()).isEqualTo("ARRANGE_CHANGE");
        assertThat(oc.getValue().getDedupKey())
                .isEqualTo("exam:ARRANGE_CHANGE:A1:" + r.getNoticeId());
        assertThat(oc.getValue().getPayloadJson()).contains("\"sourceService\":\"exam\"");
    }

    @Test
    void 考试不存在时抛业务异常() {
        when(arrangeMapper.selectById("NOPE")).thenReturn(null);
        assertThatThrownBy(() -> service.send("NOPE", req("x", "y", "name")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考试不存在");
    }

    @Test
    void 撤销_按dedupKey调通道并终结未投递outbox() {
        ExamChangeNotice notice = new ExamChangeNotice();
        notice.setId("N1");
        notice.setArrangeId("A1");
        notice.setStatus(1);
        when(noticeMapper.selectById("N1")).thenReturn(notice);

        service.cancel("A1", "N1");

        ArgumentCaptor<jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd> captor =
                ArgumentCaptor.forClass(jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd.class);
        verify(notificationPublishService).cancel(captor.capture());
        assertThat(captor.getValue().getDedupKey()).isEqualTo("exam:ARRANGE_CHANGE:A1:N1");
        // 本地发送记录置已撤销
        assertThat(notice.getStatus()).isEqualTo(3);
        verify(noticeMapper).updateById(notice);
    }

    @Test
    void 撤销_通知不属于该考试时报不存在() {
        ExamChangeNotice notice = new ExamChangeNotice();
        notice.setId("N1");
        notice.setArrangeId("OTHER");
        when(noticeMapper.selectById("N1")).thenReturn(notice);

        assertThatThrownBy(() -> service.cancel("A1", "N1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(notificationPublishService, org.mockito.Mockito.never()).cancel(any());
    }
}
