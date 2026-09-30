package jack.digityhuman.staffmanagement.service;

import jack.digityhuman.staffmanagement.api.dto.DepartmentNodeDTO;
import jack.digityhuman.staffmanagement.api.query.WorkStatus;
import jack.digityhuman.staffmanagement.dal.DepartmentStat;

import java.util.List;
import java.util.Comparator;

public interface DepartmentDirectoryService {
    List<DepartmentStat> loadDirectory();

    DepartmentStat requireByCode(String code);

    List<DepartmentStat> findByName(String name);

    DepartmentNodeDTO buildSubtree(DepartmentStat root, WorkStatus status);

    int countDepartments(DepartmentNodeDTO node);

    static void sortChildren(DepartmentNodeDTO node) {
        node.getChildren().sort(Comparator.comparing(DepartmentNodeDTO::getDeptPath));
        node.getChildren().forEach(DepartmentDirectoryService::sortChildren);
    }
}
