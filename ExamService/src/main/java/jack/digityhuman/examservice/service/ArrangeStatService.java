package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;

/** 试题分析与统计服务（04 §6 API-32~34/36~38）。 */
public interface ArrangeStatService {

    PageResult<ArrangeStatRespDtos.QuestionStat> questionList(String arrangeId, int current, int limit,
                                                              ArrangeStatRespDtos.QuestionQuery query);

    ArrangeStatRespDtos.QuestionView questionView(String arrangeId, String quesId);

    ArrangeStatRespDtos.AnswerDetail answerDetail(String arrangeId, String quesId);

    ArrangeStatRespDtos.OverviewResp overview(String arrangeId);

    ArrangeStatRespDtos.ScoreResp score(String arrangeId);

    ArrangeStatRespDtos.CorrectRateResp correctRate(String arrangeId, String dimension);
}