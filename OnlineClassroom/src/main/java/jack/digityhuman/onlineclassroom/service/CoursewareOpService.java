package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngRef;

import java.util.List;

public interface CoursewareOpService {
    KngCoursewareDtos.CountResponse takeDown(String id);

    /** 隐藏/取消隐藏（05 增补A）：hidden 独立于 status，任意状态可切换。 */
    void hide(String id, Boolean hidden);

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
