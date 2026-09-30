package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 授权对象选择接口 DTO。
 */
public final class KngSubjectDtos {

    private KngSubjectDtos() {
    }

    @Data
    public static class DeptNode {
        private String id;
        private String name;
        private Integer userCount;
        private List<DeptNode> children = new ArrayList<>();
    }

    @Data
    public static class PositionRow {
        private String name;
        private Integer userCount;
    }

    @Data
    public static class RoleRow {
        private String id;
        private String name;
        private String remark;
        private Integer userCount;
    }

    public record PageResult<T>(long total, List<T> records) {
    }
}
