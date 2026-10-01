package jack.digityhuman.speechtraining.service;

/**
 * 陪练者形象生成服务（v1.1，FP-26/28）。
 * 调 Dify 生图工作流（agent: avatar-gen，输入 prompt，输出 image_url），
 * 拿到 URL 后下载转存本地文件服务，避免依赖生图服务的临时 URL 过期。
 */
public interface AvatarGenService {

    /**
     * 按提示词生成陪练者形象
     *
     * @param prompt 形象提示词（2~200 字）
     * @return 转存后的本地图片 URL（如 /avatar/{uuid}.png）
     */
    String generate(String prompt);
}
