package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.ProjectQueryParam;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectDetailResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectListResp;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.entity.TrainingEdge;
import jack.digityhuman.speechtraining.entity.TrainingNode;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingEdgeMapper;
import jack.digityhuman.speechtraining.mapper.TrainingNodeMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.TrainingProjectService;
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
public class TrainingProjectServiceImpl extends ServiceImpl<TrainingProjectMapper, TrainingProject>
        implements TrainingProjectService {

    private final TrainingNodeMapper nodeMapper;
    private final TrainingEdgeMapper edgeMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final UserContext userContext;

    @Override
    public PageResult<ProjectListResp> listProjects(ProjectQueryParam param) {
        LambdaQueryWrapper<TrainingProject> wrapper = new LambdaQueryWrapper<>();
        if (param.getStatus() != null) {
            wrapper.eq(TrainingProject::getStatus, param.getStatus());
        }
        if (param.getProjectType() != null) {
            wrapper.eq(TrainingProject::getProjectType, param.getProjectType());
        }
        if (StringUtils.hasText(param.getResponsible())) {
            wrapper.eq(TrainingProject::getResponsiblePerson, param.getResponsible());
        }
        if (StringUtils.hasText(param.getKeyword())) {
            wrapper.like(TrainingProject::getName, param.getKeyword());
        }
        wrapper.orderByDesc(TrainingProject::getCreatedAt);

        Page<TrainingProject> page = new Page<>(param.getPage(), param.getPageSize());
        Page<TrainingProject> result = page(page, wrapper);

        List<ProjectListResp> records = result.getRecords().stream().map(p -> {
            ProjectListResp resp = new ProjectListResp();
            BeanUtils.copyProperties(p, resp);
            return resp;
        }).collect(Collectors.toList());

        return new PageResult<>(records, result.getTotal());
    }

    @Override
    public ProjectDetailResp getProjectDetail(Long id) {
        TrainingProject project = getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        ProjectDetailResp resp = new ProjectDetailResp();
        BeanUtils.copyProperties(project, resp);

        List<ScoringDimension> dimensions = scoringDimensionMapper.selectList(
                new LambdaQueryWrapper<ScoringDimension>().eq(ScoringDimension::getProjectId, id));
        resp.setScoringDimensions(dimensions.stream().map(d -> {
            ProjectSaveReq.ScoringDimensionReq req = new ProjectSaveReq.ScoringDimensionReq();
            BeanUtils.copyProperties(d, req);
            return req;
        }).collect(Collectors.toList()));

        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, id));
        if (trainer != null) {
            ProjectSaveReq.VirtualTrainerReq trainerReq = new ProjectSaveReq.VirtualTrainerReq();
            BeanUtils.copyProperties(trainer, trainerReq, "language", "inputLanguage");
            trainerReq.setLanguage(toChineseLang(trainer.getLanguage()));
            trainerReq.setInputLanguage(toChineseLang(trainer.getInputLanguage()));
            resp.setVirtualTrainer(trainerReq);
        }

        return resp;
    }

    @Override
    @Transactional
    public Long createProject(ProjectSaveReq req) {
        TrainingProject project = new TrainingProject();
        BeanUtils.copyProperties(req, project);
        project.setStatus(0);
        project.setCreatedBy(userContext.getCurrentUserId());
        project.setCreatedByName(userContext.getCurrentUserName());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedBy(userContext.getCurrentUserId());
        project.setUpdatedByName(userContext.getCurrentUserName());
        project.setUpdatedAt(LocalDateTime.now());
        save(project);

        if (req.getScoringDimensions() != null) {
            for (ProjectSaveReq.ScoringDimensionReq dimReq : req.getScoringDimensions()) {
                ScoringDimension dim = new ScoringDimension();
                BeanUtils.copyProperties(dimReq, dim, "ruleType");
                dim.setRuleType(dimReq.getRuleTypeAsInt());
                dim.setProjectId(project.getId());
                dim.setCreatedAt(LocalDateTime.now());
                dim.setUpdatedAt(LocalDateTime.now());
                scoringDimensionMapper.insert(dim);
            }
        }

        if (req.getVirtualTrainer() != null) {
            VirtualTrainer trainer = new VirtualTrainer();
            BeanUtils.copyProperties(req.getVirtualTrainer(), trainer, "language", "inputLanguage");
            trainer.setLanguage(req.getVirtualTrainer().getLanguageCode());
            trainer.setInputLanguage(req.getVirtualTrainer().getInputLanguageCode());
            trainer.setProjectId(project.getId());
            trainer.setCreatedAt(LocalDateTime.now());
            trainer.setUpdatedAt(LocalDateTime.now());
            virtualTrainerMapper.insert(trainer);
        }

        return project.getId();
    }

    @Override
    @Transactional
    public void updateProject(Long id, ProjectSaveReq req) {
        TrainingProject project = getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        // 未上传新封面时保留原有 cover_image，避免 null 覆盖
        String originalCoverImage = project.getCoverImage();
        BeanUtils.copyProperties(req, project);
        if (req.getCoverImage() == null) {
            project.setCoverImage(originalCoverImage);
        }
        project.setUpdatedBy(userContext.getCurrentUserId());
        project.setUpdatedByName(userContext.getCurrentUserName());
        project.setUpdatedAt(LocalDateTime.now());
        updateById(project);

        if (req.getScoringDimensions() != null) {
            scoringDimensionMapper.delete(
                    new LambdaQueryWrapper<ScoringDimension>().eq(ScoringDimension::getProjectId, id));
            for (ProjectSaveReq.ScoringDimensionReq dimReq : req.getScoringDimensions()) {
                ScoringDimension dim = new ScoringDimension();
                BeanUtils.copyProperties(dimReq, dim, "ruleType");
                dim.setRuleType(dimReq.getRuleTypeAsInt());
                dim.setProjectId(id);
                dim.setCreatedAt(LocalDateTime.now());
                dim.setUpdatedAt(LocalDateTime.now());
                scoringDimensionMapper.insert(dim);
            }
        }

        if (req.getVirtualTrainer() != null) {
            virtualTrainerMapper.delete(
                    new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, id));
            VirtualTrainer trainer = new VirtualTrainer();
            BeanUtils.copyProperties(req.getVirtualTrainer(), trainer, "language", "inputLanguage");
            trainer.setLanguage(req.getVirtualTrainer().getLanguageCode());
            trainer.setInputLanguage(req.getVirtualTrainer().getInputLanguageCode());
            trainer.setProjectId(id);
            trainer.setCreatedAt(LocalDateTime.now());
            trainer.setUpdatedAt(LocalDateTime.now());
            virtualTrainerMapper.insert(trainer);
        }
    }

    @Override
    @Transactional
    public void publishProject(Long id) {
        TrainingProject project = getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        List<TrainingNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<TrainingNode>().eq(TrainingNode::getProjectId, id));
        if (nodes.isEmpty()) {
            throw new BizException("请先设置流程设计");
        }

        project.setStatus(1);
        project.setUpdatedAt(LocalDateTime.now());
        updateById(project);
    }

    @Override
    @Transactional
    public void withdrawProject(Long id) {
        TrainingProject project = getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        project.setStatus(0);
        project.setUpdatedAt(LocalDateTime.now());
        updateById(project);
    }

    @Override
    @Transactional
    public void deleteProject(Long id) {
        TrainingProject project = getById(id);
        if (project == null) {
            throw new BizException("项目不存在");
        }

        removeById(id);
        nodeMapper.delete(new LambdaQueryWrapper<TrainingNode>().eq(TrainingNode::getProjectId, id));
        edgeMapper.delete(new LambdaQueryWrapper<TrainingEdge>().eq(TrainingEdge::getProjectId, id));
        scoringDimensionMapper.delete(new LambdaQueryWrapper<ScoringDimension>().eq(ScoringDimension::getProjectId, id));
        virtualTrainerMapper.delete(new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, id));
    }

    @Override
    public FlowDataResp getFlowData(Long id) {
        List<TrainingNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<TrainingNode>()
                        .eq(TrainingNode::getProjectId, id)
                        .orderByAsc(TrainingNode::getSortOrder));
        List<TrainingEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<TrainingEdge>().eq(TrainingEdge::getProjectId, id));

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
    public void saveFlow(Long projectId, FlowSaveReq req) {
        // 1. 先用前端传来的临时ID做环检测（此时ID映射关系尚未建立，但拓扑结构是正确的）
        if (hasCycle(req.getNodes(), req.getEdges())) {
            throw new BizException("流程图中存在环，请检查连线");
        }

        // 2. 删除旧数据
        nodeMapper.delete(new LambdaQueryWrapper<TrainingNode>().eq(TrainingNode::getProjectId, projectId));
        edgeMapper.delete(new LambdaQueryWrapper<TrainingEdge>().eq(TrainingEdge::getProjectId, projectId));

        // 3. 逐个插入节点，收集「前端临时ID → 数据库真实ID」的映射关系
        Map<Long, Long> idMapping = new HashMap<>();
        for (FlowSaveReq.NodeItemReq nodeReq : req.getNodes()) {
            TrainingNode node = new TrainingNode();
            BeanUtils.copyProperties(nodeReq, node, "keyPoints", "passConditionPoints");
            node.setKeyPoints(serializeKeyPoints(nodeReq.getKeyPoints()));
            node.setPassConditionPoints(toJsonArray(nodeReq.getPassConditionPoints()));
            node.setProjectId(projectId);
            nodeMapper.insert(node); // insert 后 node.getId() 即为数据库自增ID

            // 将前端传来的临时ID映射为数据库生成的真实ID
            if (nodeReq.getId() != null && nodeReq.getId() != node.getId()) {
                idMapping.put(nodeReq.getId(), node.getId());
            }
        }

        // 4. 保存连线，用映射表将旧ID替换为真实ID
        for (FlowSaveReq.EdgeItemReq edgeReq : req.getEdges()) {
            TrainingEdge edge = new TrainingEdge();
            edge.setProjectId(projectId);
            edge.setSourceNodeId(idMapping.getOrDefault(edgeReq.getSourceNodeId(), edgeReq.getSourceNodeId()));
            edge.setTargetNodeId(idMapping.getOrDefault(edgeReq.getTargetNodeId(), edgeReq.getTargetNodeId()));
            edge.setWeight(edgeReq.getWeight() != null ? edgeReq.getWeight() : 1);
            edge.setCreatedAt(LocalDateTime.now());
            edgeMapper.insert(edge);
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

    private String toChineseLang(String code) {
        if (code == null) return null;
        return switch (code.toLowerCase()) {
            case "mandarin" -> "普通话";
            case "cantonese" -> "粤语";
            default -> code;
        };
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

    /**
     * 将前端传来的流转条件要点转换为合法JSON数组字符串（数据库列为JSON类型）。
     * 支持：单个ID字符串、逗号分隔字符串、JSON数组/对象字符串、List
     */
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
