package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngRef;

import java.util.List;

public interface CoursewareOpService {
    KngCoursewareDtos.CountResponse takeDown(String id);

    KngCoursewareDtos.CountResponse batchTakeDown(KngCoursewareDtos.IdsRequest request);

    KngCoursewareDtos.CountResponse batchPutOn(KngCoursewareDtos.IdsRequest request);

    KngCoursewareDtos.CountResponse move(KngCoursewareDtos.MoveRequest request);

    KngCoursewareDtos.IdResponse copy(
            String id, KngCoursewareDtos.CopyRequest request, String userId);

    KngCoursewareDtos.CountResponse delete(String id);

    KngCoursewareDtos.CountResponse batchDelete(KngCoursewareDtos.IdsRequest request);

    KngCoursewareFile downloadFile(String id);

    List<KngRef> references(String id);

    KngCoursewareDtos.DownloadRecordResponse downloadRecords(String id);
}
