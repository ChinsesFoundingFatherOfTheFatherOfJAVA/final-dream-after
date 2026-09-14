package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.spring.service.IService;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.TemplateQueryParam;
import jack.digityhuman.speechtraining.dto.req.TemplateSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateDetailResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateListResp;
import jack.digityhuman.speechtraining.entity.SpeechTemplate;

public interface SpeechTemplateService extends IService<SpeechTemplate> {

    PageResult<TemplateListResp> listTemplates(TemplateQueryParam param);

    TemplateDetailResp getTemplateDetail(Long id);

    Long createTemplate(TemplateSaveReq req);

    void updateTemplate(Long id, TemplateSaveReq req);

    void deleteTemplate(Long id);

    FlowDataResp getFlowData(Long id);

    void saveFlow(Long templateId, FlowSaveReq req);
}
