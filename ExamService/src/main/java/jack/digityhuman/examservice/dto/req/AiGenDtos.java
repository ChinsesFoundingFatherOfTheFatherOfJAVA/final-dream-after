package jack.digityhuman.examservice.dto.req;

import lombok.Data;

/** AI 建题请求容器（03 §7）。 */
public final class AiGenDtos {

    private AiGenDtos() {
    }

    /** 7.1 POST /ques/ai-generate。 */
    @Data
    public static class AiGenReq {
        /** 素材文本：粘贴 ≤5000 字，或 /file/parse 解析后的文本 ≤20000 字。 */
        private String materialText;
        /** 各题型数量，每项 0~35，合计 >0。 */
        private Counts counts;
    }

    @Data
    public static class Counts {
        private Integer single = 0;
        private Integer multi = 0;
        private Integer judge = 0;
        private Integer fill = 0;
        private Integer answer = 0;
    }
}