package jack.digityhuman.staffmanagement.role.dto;

import lombok.Data;

/**
 * 角色勾选权限点的请求体（FP-14 即时保存）。
 *
 * <p>一次只提交一个权限点：前端是"点一下复选框就保存"，没有整树覆盖的语义，
 * 后端因此也不需要"先删后插"这种会短暂清空权限的写法。
 */
@Data
public class PermissionGrantRequest {

    /** 权限点 ID，字符串传输防精度丢失。 */
    private String permissionId;
}
