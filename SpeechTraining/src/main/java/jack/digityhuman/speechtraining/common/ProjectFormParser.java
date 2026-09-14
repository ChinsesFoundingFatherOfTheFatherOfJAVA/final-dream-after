package jack.digityhuman.speechtraining.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 将 multipart/form-data 请求解析为 {@link ProjectSaveReq}。
 *
 * 支持两种前端提交方式：
 * 1. 整体JSON：FormData 中携带一个名为 data / project / json 的字段，值为项目完整JSON字符串
 * 2. 逐字段：FormData 中各字段名与 ProjectSaveReq 属性一致，
 *    scoringDimensions / virtualTrainer 以JSON字符串形式提交
 */
@Slf4j
@Component
public class ProjectFormParser {

    private static final String[] JSON_FIELD_NAMES = {"data", "project", "json", "body"};

    private final ObjectMapper objectMapper;

    public ProjectFormParser() {
        this.objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
        this.objectMapper.registerModule(module);
    }

    /** 从请求中取出上传的文件（任意文件字段，优先 coverImage/file/cover） */
    public MultipartFile extractFile(MultipartHttpServletRequest request) {
        if (!request.getFileMap().isEmpty()) {
            for (String preferred : new String[]{"coverImage", "cover", "file", "image"}) {
                MultipartFile f = request.getFile(preferred);
                if (f != null && !f.isEmpty()) return f;
            }
            return request.getFileMap().values().stream()
                    .filter(f -> f != null && !f.isEmpty())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public ProjectSaveReq parse(MultipartHttpServletRequest request) {
        // 方式1：整体JSON字段
        for (String name : JSON_FIELD_NAMES) {
            String json = request.getParameter(name);
            if (StringUtils.hasText(json)) {
                try {
                    return objectMapper.readValue(json, ProjectSaveReq.class);
                } catch (Exception e) {
                    throw new BizException("解析项目数据失败: " + e.getMessage());
                }
            }
        }
        // 方式2：逐字段
        return parseFromParameters(request);
    }

    @SuppressWarnings("unchecked")
    private ProjectSaveReq parseFromParameters(MultipartHttpServletRequest request) {
        ProjectSaveReq req = new ProjectSaveReq();
        req.setName(param(request, "name"));
        req.setDescription(param(request, "description"));
        req.setCode(param(request, "code"));
        req.setProjectType(intParam(request, "projectType"));
        req.setAssignType(intParam(request, "assignType"));
        req.setCompletionStandard(intParam(request, "completionStandard"));
        req.setPassScore(intParam(request, "passScore"));
        req.setPassStrategy(intParam(request, "passStrategy"));
        req.setPassStrategyN(intParam(request, "passStrategyN"));
        req.setDialogueMode(intParam(request, "dialogueMode"));
        req.setResponsiblePerson(param(request, "responsiblePerson"));
        req.setResponsiblePersonName(param(request, "responsiblePersonName"));
        req.setStartTime(dateParam(request, "startTime"));
        req.setEndTime(dateParam(request, "endTime"));

        // coverImage 若作为文本字段传入（保留原路径），直接使用
        String coverImage = param(request, "coverImage");
        if (StringUtils.hasText(coverImage)) {
            req.setCoverImage(coverImage);
        }

        String dimsJson = param(request, "scoringDimensions");
        if (StringUtils.hasText(dimsJson)) {
            try {
                List<ProjectSaveReq.ScoringDimensionReq> dims =
                        objectMapper.readValue(dimsJson, objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, ProjectSaveReq.ScoringDimensionReq.class));
                req.setScoringDimensions(dims);
            } catch (Exception e) {
                throw new BizException("解析评分维度失败: " + e.getMessage());
            }
        }

        String trainerJson = param(request, "virtualTrainer");
        if (StringUtils.hasText(trainerJson)) {
            try {
                req.setVirtualTrainer(objectMapper.readValue(trainerJson, ProjectSaveReq.VirtualTrainerReq.class));
            } catch (Exception e) {
                throw new BizException("解析虚拟陪练者设置失败: " + e.getMessage());
            }
        }

        return req;
    }

    private String param(MultipartHttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return StringUtils.hasText(value) ? value : null;
    }

    private Integer intParam(MultipartHttpServletRequest request, String name) {
        String value = param(request, name);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime dateParam(MultipartHttpServletRequest request, String name) {
        String value = param(request, name);
        if (value == null) return null;
        try {
            return (LocalDateTime) objectMapper.readValue("\"" + value + "\"", LocalDateTime.class);
        } catch (Exception e) {
            log.warn("解析日期失败: {}={}", name, value);
            return null;
        }
    }

    /** 供调试：查看请求参数 */
    public Map<String, String[]> parameters(MultipartHttpServletRequest request) {
        return request.getParameterMap();
    }
}
