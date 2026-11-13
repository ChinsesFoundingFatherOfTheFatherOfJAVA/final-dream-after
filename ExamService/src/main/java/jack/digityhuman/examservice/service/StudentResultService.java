package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;

/**
 * 学员端结果与答卷详情（04 §11.12 / §11.13）。
 *
 * <p>两个方法都要过"可见性"这一关：结果页看 `answerView.viewScore` 等开关，
 * 答卷详情看 `viewAnswerDetail` + `viewAnswerTimeRange` 四值语义。
 */
public interface StudentResultService {

    /** §11.12 结果页；`graded=false` 时 score/passed/rankNo 为 null（DER-03）。 */
    StudentResultRespDtos.Result result(String userId, String attemptId);

    /**
     * §11.13 答卷详情。
     *
     * @param wrongOnly 1=只看错题（真过滤，不是高亮）；null 时取 `answerView.wrongOnly` 默认值
     */
    StudentResultRespDtos.PaperDetail paperDetail(String userId, String attemptId, Integer wrongOnly);
}
