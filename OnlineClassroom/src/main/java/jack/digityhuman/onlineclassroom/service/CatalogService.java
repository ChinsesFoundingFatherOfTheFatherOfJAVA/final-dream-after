package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCatalogDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;

import java.util.List;

public interface CatalogService {
    List<KngCatalogDtos.TreeNode> tree();

    KngCatalogDtos.Detail detail(String id);

    KngCatalogDtos.IdResponse create(KngCatalogDtos.SaveRequest request, String userId);

    KngCatalogDtos.IdResponse update(String id, KngCatalogDtos.SaveRequest request, String userId);

    void delete(String id);

    KngCatalogDtos.CountResponse batchCreate(KngCatalogDtos.BatchRequest request, String userId);

    KngCatalogDtos.CountResponse move(String id, KngCatalogDtos.MoveRequest request);

    KngCatalogDtos.CountResponse moveCourses(String id, KngCatalogDtos.MoveRequest request);

    KngCatalogDtos.CountResponse mergeToParent(String id);

    List<KngCatalog> allCatalogs();
}
