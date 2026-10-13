package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

/** 考核点响应容器（03 §4）。 */
public final class PointRespDtos {

    private PointRespDtos() {
    }

    /** 4.1 列表行。 */
    @Data
    public static class PointItem {
        private String id;
        private String name;
        /** 1 = 建库时自动生成的默认考点（不可删）。 */
        private Integer defaultPoint;
    }
}