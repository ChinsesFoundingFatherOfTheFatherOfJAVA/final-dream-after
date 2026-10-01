package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.dify.DifyWorkflowSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 陪练者形象生成服务实现（v1.1，FP-26/28）。
 * 调 Dify 生图工作流（agent: avatar-gen，输入 prompt，输出 image_url），
 * 拿到 URL 后下载转存本地文件服务，避免依赖生图服务的临时 URL 过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarGenServiceImpl implements AvatarGenService {

    private final DifyWorkflowSupport difyWorkflowSupport;
    private final FileStorageService fileStorageService;
    private final RestTemplate restTemplate;

    /**
     * 按提示词生成陪练者形象
     *
     * @return 转存后的本地图片 URL（如 /avatar/{uuid}.png）
     */
    @Override
    public String generate(String prompt) {
        // 1. 提示词校验（2~200 字）
        String text = prompt == null ? "" : prompt.trim();
        if (text.length() < 2 || text.length() > 200) {
            throw new BizException("请输入形象提示词（2~200字）");
        }
        // 构图约束：生图插件固定输出 1024x1024 方图，学员端形象卡同为 1:1 方形（展示时不裁剪），
        // 追加构图要求保证人物主体居中、四周留边，避免头肩贴边出框
        String fullPrompt = text + "，人物半身居中构图，头顶与画面顶部保留适当留白，左右两侧留出背景余量";

        // 2. 调 Dify 生图工作流：输入 prompt，输出 image_url
        Map<String, Object> outputs;
        try {
            outputs = difyWorkflowSupport.runWorkflow("avatar-gen", Map.of("prompt", fullPrompt));
        } catch (Exception e) {
            log.error("Dify生图工作流调用失败", e);
            throw new BizException("形象生成失败，请重试或上传本地图片");
        }
        String imageUrl = outputs == null ? null : String.valueOf(outputs.get("image_url"));
        if (imageUrl == null || imageUrl.isBlank() || "null".equals(imageUrl)) {
            log.warn("Dify生图工作流未返回image_url: {}", outputs);
            throw new BizException("形象生成失败，请重试或上传本地图片");
        }

        // 3. 下载图片并转存本地（不依赖外部临时 URL）
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(imageUrl, HttpMethod.GET, null, byte[].class);
            byte[] image = response.getBody();
            if (image == null || image.length == 0) {
                throw new BizException("形象生成失败，请重试或上传本地图片");
            }
            return fileStorageService.saveAvatarImage(image, resolveExtension(imageUrl));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("生图下载转存失败: {}", imageUrl, e);
            throw new BizException("形象生成失败，请重试或上传本地图片");
        }
    }

    /** 从 URL 提取扩展名（默认 png） */
    private String resolveExtension(String url) {
        int dot = url.lastIndexOf('.');
        int slash = url.lastIndexOf('/');
        if (dot > slash && dot < url.length() - 1) {
            String ext = url.substring(dot + 1).toLowerCase();
            // 去除查询参数
            int q = ext.indexOf('?');
            if (q > 0) ext = ext.substring(0, q);
            if (ext.matches("[a-z0-9]{2,5}")) {
                return "." + ext;
            }
        }
        return ".png";
    }
}
