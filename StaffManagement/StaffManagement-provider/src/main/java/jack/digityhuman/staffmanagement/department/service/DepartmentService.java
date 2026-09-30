package jack.digityhuman.staffmanagement.department.service;

import jack.digityhuman.staffmanagement.entity.SysDepartment;

import java.util.List;

public interface DepartmentService {
    String PATH_SEPARATOR = "_";

    SysDepartment requireById(String deptId);

    static String childPathPattern(String deptPath) {
        return deptPath + "\\_%";
    }

    List<SysDepartment> listTree(String keyword);
}
