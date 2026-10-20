package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 用户设置请求容器（04 §9）。 */
public final class ArrangeSettingDtos {

    private ArrangeSettingDtos() {
    }

    @Data
    public static class ColumnSetting {
        private List<Column> columns;
    }

    @Data
    public static class Column {
        private String key;
        private Boolean visible;
        private Integer order;
    }
}