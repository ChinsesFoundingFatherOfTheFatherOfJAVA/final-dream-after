package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.StringCountDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 选人抽屉数据源（04 §5 API-48/49）：部门树、岗位。 */
@RestController
@RequestMapping("/api/exam/arrange/options")
@RequiredArgsConstructor
public class ArrangeOptionsController {

    private final OrgQueryService orgQueryService;

    /** API-48 部门树（走 Dubbo，不直读 StaffManagement 表）。 */
    @GetMapping("/departments")
    public ApiResponse<Map<String, Object>> departments() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("list", orgQueryService.listAllDepartments());
        } catch (Exception e) {
            out.put("list", java.util.List.of());
        }
        return ApiResponse.success(out);
    }

    /** API-49 岗位分页（keyword 搜索）。 */
    @GetMapping("/positions")
    public ApiResponse<Map<String, Object>> positions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int limit) {
        // StaffManagement 的 PageResult 与本平台 common 的不是同一个类，这里转成统一形状
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var p = orgQueryService.pagePositionStats(keyword, current, limit);
            out.put("list", p.getRecords());
            out.put("total", p.getTotal());
            out.put("page", current);
            out.put("size", limit);
        } catch (Exception e) {
            out.put("list", java.util.List.of());
            out.put("total", 0);
            out.put("page", current);
            out.put("size", limit);
        }
        return ApiResponse.success(out);
    }

    /**
     * F3 创建人选择器：按部门 + 指定字段（姓名/账号/工号）查在职用户。
     *
     * <p>走 Dubbo {@code listActiveCandidates}，不直读 {@code sys_*} 表。
     * 返回工号与岗位，供抽屉按原平台列展示；分页在内存里做（部门同时在职人员量级不大）。
     */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deptPath,
            @RequestParam(defaultValue = "name") String field,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        int total = 0;
        try {
            List<SysUserDTO> users = orgQueryService.listActiveCandidates(deptPath, keyword);
            if (users == null) {
                users = List.of();
            }
            // 后端已按"姓名/账号/工号"做了模糊匹配；这里再按用户选的字段收窄
            if (StringUtils.hasText(keyword) && StringUtils.hasText(field) && !"all".equals(field)) {
                String kw = keyword.trim().toLowerCase();
                users = users.stream().filter(u -> matchField(u, field, kw)).toList();
            }
            total = users.size();
            int from = Math.max(0, (current - 1) * limit);
            int to = Math.min(users.size(), from + limit);
            if (from < to) {
                for (SysUserDTO u : users.subList(from, to)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(u.getId()));
                    m.put("account", u.getAccount());
                    m.put("fullname", u.getName());
                    m.put("employeeNo", u.getEmployeeNo());
                    m.put("deptName", u.getDeptName());
                    m.put("positionName", u.getPositionName());
                    list.add(m);
                }
            }
        } catch (Exception e) {
            // 组织服务不可用时返回空列表，抽屉显示"暂无数据"
        }
        out.put("list", list);
        out.put("total", total);
        out.put("page", current);
        out.put("size", limit);
        return ApiResponse.success(out);
    }

    /** 按字段比较：name / account / employeeNo；其余按姓名。 */
    private boolean matchField(SysUserDTO u, String field, String kw) {
        String v = switch (field) {
            case "account" -> u.getAccount();
            case "employeeNo" -> u.getEmployeeNo();
            default -> u.getName();
        };
        return v != null && v.toLowerCase().contains(kw);
    }
}
