package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngSubjectDtos;

import java.util.List;

public interface SubjectService {
    List<KngSubjectDtos.DeptNode> deptTree();

    KngSubjectDtos.PageResult<KngSubjectDtos.PositionRow> positions(
            int page, int pageSize, String name);

    KngSubjectDtos.PageResult<KngSubjectDtos.RoleRow> roles(
            int page, int pageSize, String name);
}
