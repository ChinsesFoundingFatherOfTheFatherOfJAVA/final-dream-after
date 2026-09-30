package jack.digityhuman.staffmanagement.role.service;

import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.role.dto.RoleMemberQuery;
import jack.digityhuman.staffmanagement.role.vo.RoleMemberVo;

import java.util.List;
import java.util.Map;

public interface RoleMemberService {
    PageVo<RoleMemberVo> listMembers(String roleId, RoleMemberQuery query);

    Map<String, Integer> addMembers(String roleId, List<String> userIds);

    void removeMember(String roleId, String userId);

    Map<String, Integer> removeMembers(String roleId, List<String> userIds);
}
