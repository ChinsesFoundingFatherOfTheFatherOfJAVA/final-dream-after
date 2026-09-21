package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.TemplateQueryParam;
import jack.digityhuman.speechtraining.dto.req.TemplateSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateDetailResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateListResp;
import jack.digityhuman.speechtraining.entity.SpeechTemplate;
import jack.digityhuman.speechtraining.entity.TemplateEdge;
import jack.digityhuman.speechtraining.entity.TemplateNode;
import jack.digityhuman.speechtraining.mapper.SpeechTemplateMapper;
import jack.digityhuman.speechtraining.mapper.TemplateEdgeMapper;
import jack.digityhuman.speechtraining.mapper.TemplateNodeMapper;
import jack.digityhuman.speechtraining.service.SpeechTemplateService;
import jack.digityhuman.speechtraining.service.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SpeechTemplateServiceImpl extends ServiceImpl<SpeechTemplateMapper, SpeechTemplate>
        implements SpeechTemplateService {

    private final TemplateNodeMapper templateNodeMapper;
    private final TemplateEdgeMapper templateEdgeMapper;
    private final UserContext userContext;

    @Override
    public PageResult<TemplateListResp> listTemplates(TemplateQueryParam param) {
        LambdaQueryWrapper<SpeechTemplate> wrapper = new LambdaQueryWrapper<>();
        if (param.getStatus() != null) {
            wrapper.eq(SpeechTemplate::getStatus, param.getStatus());
        }
        if (StringUtils.hasText(param.getKeyword())) {
            wrapper.like(SpeechTemplate::getName, param.getKeyword());
        }
        wrapper.orderByDesc(SpeechTemplate::getCreatedAt);

        Page<SpeechTemplate> page = new Page<>(param.getPage(), param.getPageSize());
        Page<SpeechTemplate> result = page(page, wrapper);

        List<TemplateListResp> records = result.getRecords().stream().map(t -> {
            TemplateListResp resp = new TemplateListResp();
            BeanUtils.copyProperties(t, resp);
            return resp;
        }).collect(Collectors.toList());

        return new PageResult<>(records, result.getTotal());
    }

    @Override
    public TemplateDetailResp getTemplateDetail(Long id) {
        SpeechTemplate template = getById(id);
        if (template == null) {
            throw new BizException("模板不存在");
        }
        TemplateDetailResp resp = new TemplateDetailResp();
        BeanUtils.copyProperties(template, resp);
        return resp;
    }

    @Override
    @Transactional
    public Long createTemplate(TemplateSaveReq req) {
        SpeechTemplate template = new SpeechTemplate();
        template.setName(req.getName());
        template.setDescription(req.getDescription());
        template.setPermission(req.getPermission() != null ? req.getPermission() : 0);
        template.setStatus(0);
        template.setCreatedBy(userContext.getCurrentUserId());
        template.setCreatedByName(userContext.getCurrentUserName());
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        save(template);
        return template.getId();
    }

    @Override
    @Transactional
    public void updateTemplate(Long id, TemplateSaveReq req) {
        SpeechTemplate template = getById(id);
        if (template == null) {
            throw new BizException("模板不存在");
        }
        template.setName(req.getName());
        template.setDescription(req.getDescription());
        template.setPermission(req.getPermission());
        template.setUpdatedAt(LocalDateTime.now());
        updateById(template);
    }

    @Override
    @Transactional
    public void deleteTemplate(Long id) {
        SpeechTemplate template = getById(id);
        if (template == null) {
            throw new BizException("模板不存在");
        }
        removeById(id);
        templateNodeMapper.delete(new LambdaQueryWrapper<TemplateNode>().eq(TemplateNode::getTemplateId, id));
        templateEdgeMapper.delete(new LambdaQueryWrapper<TemplateEdge>().eq(TemplateEdge::getTemplateId, id));
    }

    @Override
    public FlowDataResp getFlowData(Long id) {
        List<TemplateNode> nodes = templateNodeMapper.selectList(
                new LambdaQueryWrapper<TemplateNode>()
                        .eq(TemplateNode::getTemplateId, id)
                        .orderByAsc(TemplateNode::getSortOrder));
        List<TemplateEdge> edges = templateEdgeMapper.selectList(
                new LambdaQueryWrapper<TemplateEdge>().eq(TemplateEdge::getTemplateId, id));

        FlowDataResp resp = new FlowDataResp();
        resp.setNodes(nodes.stream().map(n -> {
            FlowDataResp.NodeResp nr = new FlowDataResp.NodeResp();
            BeanUtils.copyProperties(n, nr, "keyPoints", "passConditionPoints");
            nr.setKeyPoints(parseKeyPoints(n.getKeyPoints()));
            nr.setPassConditionPoints(parseJson(n.getPassConditionPoints()));
            return nr;
        }).collect(Collectors.toList()));
        resp.setEdges(edges.stream().map(e -> {
            FlowDataResp.EdgeResp er = new FlowDataResp.EdgeResp();
            BeanUtils.copyProperties(e, er);
            return er;
        }).collect(Collectors.toList()));
        return resp;
    }

    @Override
    @Transactional
    public void saveFlow(Long templateId, FlowSaveReq req) {
        // 1. 用前端临时ID做环检测
        if (hasCycle(req.getNodes(), req.getEdges())) {
            throw new BizException("流程图中存在环，请检查连线");
        }

        // 2. 删除旧数据
        templateNodeMapper.delete(new LambdaQueryWrapper<TemplateNode>().eq(TemplateNode::getTemplateId, templateId));
        templateEdgeMapper.delete(new LambdaQueryWrapper<TemplateEdge>().eq(TemplateEdge::getTemplateId, templateId));

        // 3. 逐个插入节点，收集「前端临时ID → 数据库真实ID」的映射关系
        Map<Long, Long> idMapping = new HashMap<>();
        for (FlowSaveReq.NodeItemReq nodeReq : req.getNodes()) {
            TemplateNode node = new TemplateNode();
            BeanUtils.copyProperties(nodeReq, node, "keyPoints", "passConditionPoints");
            // 前端传的 id 只是临时ID（用于连线引用），不能作为主键插入，否则会与已有节点主键冲突
            node.setId(null);
            node.setKeyPoints(serializeKeyPoints(nodeReq.getKeyPoints()));
            node.setPassConditionPoints(toJsonArray(nodeReq.getPassConditionPoints()));
            node.setTemplateId(templateId);
            templateNodeMapper.insert(node); // 主键交由自增生成，insert 后 node.getId() 即为真实ID

            if (nodeReq.getId() != null) {
                idMapping.put(nodeReq.getId(), node.getId());
            }
        }

        // 4. 保存连线，用映射表将旧ID替换为真实ID
        for (FlowSaveReq.EdgeItemReq edgeReq : req.getEdges()) {
            TemplateEdge edge = new TemplateEdge();
            edge.setTemplateId(templateId);
            edge.setSourceNodeId(idMapping.getOrDefault(edgeReq.getSourceNodeId(), edgeReq.getSourceNodeId()));
            edge.setTargetNodeId(idMapping.getOrDefault(edgeReq.getTargetNodeId(), edgeReq.getTargetNodeId()));
            edge.setWeight(edgeReq.getWeight() != null ? edgeReq.getWeight() : 1);
            edge.setCreatedAt(LocalDateTime.now());
            templateEdgeMapper.insert(edge);
        }
    }

    private boolean hasCycle(List<FlowSaveReq.NodeItemReq> nodes, List<FlowSaveReq.EdgeItemReq> edges) {
        if (nodes == null || nodes.isEmpty()) return false;

        Map<Long, List<Long>> adjacency = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();

        for (FlowSaveReq.NodeItemReq node : nodes) {
            if (node.getId() != null) {
                inDegree.putIfAbsent(node.getId(), 0);
            }
        }

        if (edges != null) {
            for (FlowSaveReq.EdgeItemReq edge : edges) {
                if (edge.getSourceNodeId() != null && edge.getTargetNodeId() != null) {
                    adjacency.computeIfAbsent(edge.getSourceNodeId(), k -> new ArrayList<>())
                            .add(edge.getTargetNodeId());
                    inDegree.merge(edge.getTargetNodeId(), 1, Integer::sum);
                    inDegree.putIfAbsent(edge.getSourceNodeId(), 0);
                }
            }
        }

        Queue<Long> queue = new LinkedList<>();
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        int count = 0;
        while (!queue.isEmpty()) {
            Long nodeId = queue.poll();
            count++;
            for (Long neighbor : adjacency.getOrDefault(nodeId, Collections.emptyList())) {
                inDegree.merge(neighbor, -1, Integer::sum);
                if (inDegree.get(neighbor) == 0) queue.add(neighbor);
            }
        }

        return count != inDegree.size();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private List<FlowDataResp.KeyPointItem> parseKeyPoints(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON_MAPPER.readValue(json, JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, FlowDataResp.KeyPointItem.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeKeyPoints(List<FlowSaveReq.KeyPointItem> keyPoints) {
        if (keyPoints == null || keyPoints.isEmpty()) return null;
        try {
            return JSON_MAPPER.writeValueAsString(keyPoints);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析数据库中的JSON列，返回List/Map等，供响应使用 */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON_MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** 将前端传来的流转条件要点转换为合法JSON数组字符串（数据库列为JSON类型） */
    private String toJsonArray(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof List) {
                return JSON_MAPPER.writeValueAsString(value);
            }
            String s = value.toString().trim();
            if (s.isEmpty()) return null;
            if (s.startsWith("[") || s.startsWith("{")) {
                return s;
            }
            List<String> ids = Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .collect(Collectors.toList());
            return JSON_MAPPER.writeValueAsString(ids);
        } catch (Exception e) {
            return null;
        }
    }
}
