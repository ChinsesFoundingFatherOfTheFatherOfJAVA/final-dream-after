package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 考核点请求容器（03 §4）。 */
public final class PointDtos {

    private PointDtos() {
    }

    /** 4.2 编辑抽屉保存：增删改合并提交（全量覆盖）。 */
    @Data
    public static class BatchReq {
        private String libId;
        private List<Item> points;

        @Data
        public static class Item {
            /** 为空表示新增。 */
            private String id;
            private String name;
        }
    }
}