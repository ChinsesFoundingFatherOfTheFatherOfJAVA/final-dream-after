package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.speechtraining.dto.resp.AiScriptGenResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyScriptGenService {

    private final DifyWorkflowSupport difyWorkflowSupport;

    /**
     * 调用Dify话术生成智能体，根据场景和轮次生成对话流程
     */
    public AiScriptGenResp generateScript(String description, Integer rounds) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("description", description);
        inputs.put("rounds", rounds);

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("script-gen", inputs);

        AiScriptGenResp resp = new AiScriptGenResp();

        String nodesJson = (String) outputs.get("nodes");
        String edgesJson = (String) outputs.get("edges");

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            List nodesList = mapper.readValue(nodesJson, List.class);
            List<AiScriptGenResp.NodeItem> nodes = new ArrayList<>();
            for (Object obj : nodesList) {
                Map nodeMap = (Map) obj;
                AiScriptGenResp.NodeItem item = new AiScriptGenResp.NodeItem();
                item.setNodeType(toInt(nodeMap.get("nodeType")));
                item.setNodeName((String) nodeMap.get("nodeName"));
                item.setStandardScript((String) nodeMap.get("standardScript"));
                item.setAnalysis((String) nodeMap.get("analysis"));
                if (nodeMap.get("positionX") != null) {
                    item.setPositionX(toFloat(nodeMap.get("positionX")));
                }
                if (nodeMap.get("positionY") != null) {
                    item.setPositionY(toFloat(nodeMap.get("positionY")));
                }
                // 解析keyPoints
                Object kpObj = nodeMap.get("keyPoints");
                if (kpObj instanceof List) {
                    List kpList = (List) kpObj;
                    List<AiScriptGenResp.KeyPoint> keyPoints = new ArrayList<>();
                    for (Object kp : kpList) {
                        Map kpMap = (Map) kp;
                        AiScriptGenResp.KeyPoint kpItem = new AiScriptGenResp.KeyPoint();
                        kpItem.setId((String) kpMap.get("id"));
                        kpItem.setContent((String) kpMap.get("content"));
                        if (kpMap.get("order") != null) {
                            kpItem.setOrder(toInt(kpMap.get("order")));
                        }
                        keyPoints.add(kpItem);
                    }
                    item.setKeyPoints(keyPoints);
                }
                nodes.add(item);
            }
            resp.setNodes(nodes);

            List edgesList = mapper.readValue(edgesJson, List.class);
            List<AiScriptGenResp.EdgeItem> edges = new ArrayList<>();
            for (Object obj : edgesList) {
                Map edgeMap = (Map) obj;
                AiScriptGenResp.EdgeItem edge = new AiScriptGenResp.EdgeItem();
                edge.setSource(toInt(edgeMap.get("source")));
                edge.setTarget(toInt(edgeMap.get("target")));
                edge.setWeight(toInt(edgeMap.get("weight")));
                edges.add(edge);
            }
            resp.setEdges(edges);

        } catch (Exception e) {
            log.error("解析Dify话术生成结果失败", e);
            throw new RuntimeException("解析AI生成结果失败: " + e.getMessage());
        }

        return resp;
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return 0f;
    }

    /**
     * 上传文件生成话术：提取文件文本内容，拼接到描述中，再调用Dify生成
     */
    public AiScriptGenResp generateScriptWithFile(String description, MultipartFile file) {
        String fileText = extractText(file);
        String fullDescription = description;
        if (fileText != null && !fileText.isBlank()) {
            fullDescription = description + "\n\n参考资料内容：\n" + fileText;
        }
        return generateScript(fullDescription, 5);
    }

    private String extractText(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null) return null;
        try {
            if (filename.toLowerCase().endsWith(".pdf")) {
                try (var doc = Loader.loadPDF(file.getBytes())) {
                    return new PDFTextStripper().getText(doc);
                }
            } else if (filename.toLowerCase().endsWith(".docx")) {
                try (var doc = new XWPFDocument(file.getInputStream())) {
                    return new XWPFWordExtractor(doc).getText();
                }
            }
        } catch (Exception e) {
            log.warn("文件文本提取失败: {}", filename, e);
        }
        return null;
    }
}
