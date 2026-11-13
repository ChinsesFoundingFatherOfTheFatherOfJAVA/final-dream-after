package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 考生管理请求容器（04 §5 API-19~26/30）。 */
public final class ArrangeStudentDtos {

    private ArrangeStudentDtos() {
    }

    /** API-19 考生列表筛选（筛选语义统一走本平台字段名）。 */
    @Data
    public static class ListQuery {
        private String keyword;
        /** -1 全部；0 未开始 1 考试中 2 批阅中 3 已完成 4 重新考试 */
        private Integer examStatus = -1;
        /** -1 全部；0 不通过 1 通过 2 未知 */
        private Integer passStatus = -1;
        /** -1 全部；1 启用 2 禁用 3 删除 */
        private Integer accountStatus = -1;
        private List<String> deptIds;
        private List<String> positionIds;
        /**
         * 岗位筛选：岗位在本平台只有名称（SysUserDTO / sys_user 都没有岗位 ID），
         * 所以按名称匹配 position_name，不能像 deptIds 那样按 ID 匹配。
         */
        private List<String> positionNames;
        /** 加入方式：-1/null 全部；1 指派 2 链接 3 导入（原站 joinType） */
        private Integer joinType;
        /** 开考时间起（含），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss */
        private String examStartBegin;
        /** 开考时间止（含），格式同上 */
        private String examStartEnd;
        private List<String> visibleColumns;
    }

    /** API-21 添加学员。 */
    @Data
    public static class AddReq {
        private List<String> userIds;
        private Integer joinType = 1;
    }

    /** API-22 批量移除。 */
    @Data
    public static class BatchRemoveReq {
        private List<String> studentIds;
        /** 有作答记录时必须 true 才允许移除 */
        private Boolean force = false;
    }

    /** API-24 同步试卷。 */
    @Data
    public static class SyncReq {
        /** unanswered | all */
        private String mode = "unanswered";
    }

    /** API-26 催促。 */
    @Data
    public static class UrgeReq {
        /** exam | grade */
        private String type = "exam";
        private List<String> studentIds;
    }
}