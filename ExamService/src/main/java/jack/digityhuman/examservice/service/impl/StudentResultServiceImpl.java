package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.AnswerViewPolicy;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.StudentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 学员端结果与答卷详情实现（04 §11.12 / §11.13）。
 *
 * <p>三处最容易写错、也最容易引发投诉的地方：
 * <ol>
 *   <li><b>`graded=false` 时 score/passed/rankNo 必须是 null</b>，不能给 0（DER-03）；</li>
 *   <li><b>`wrongOnly=1` 是真过滤</b>：答对的大题连标题都不出现，不是加个 CSS 高亮；</li>
 *   <li><b>可见性给原因</b>：不可看时 message 必须是"考试结束后允许查看"这类可展示文案，
 *       统一由 {@link AnswerViewPolicy} 出，与入场页 tooltip 同源。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentResultServiceImpl implements StudentResultService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int ERR_NOT_FOUND = 40024;
    /** 答卷详情不可查看（04 §11.13）。 */
    private static final int ERR_DETAIL_BLOCKED = 40055;

    private static final int STATUS_GRADING = 2;
    private static final int STATUS_FINISHED = 3;
    private static final int STATUS_INVALID = 4;
    private static final int FLAG_PENDING = 3;

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] CN_NUM = { "一", "二", "三", "四", "五", "六", "七", "八", "九", "十" };

    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;
    private final ExamArrangeSignatureMapper signatureMapper;

    // ---------------- §11.12 结果 ----------------

    @Override
    public StudentResultRespDtos.Result result(String userId, String attemptId) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        Map<String, Object> advanced = readJson(arrange == null ? null : arrange.getAdvancedSettings());
        List<ExamAttemptAnswer> answers = answersOf(attemptId);
        Map<String, ExamQuestion> questions = questionsOf(attempt);
        Map<String, PaperMeta> meta = paperMetaOf(attempt);
        boolean graded = isGraded(attempt, answers);

        StudentResultRespDtos.Result r = new StudentResultRespDtos.Result();
        r.setArrangeId(attempt.getArrangeId());
        r.setAttemptId(attempt.getId());
        r.setName(arrange == null ? "" : arrange.getName());
        r.setStatus(attempt.getStatus());
        r.setGraded(graded);
        BigDecimal totalScore = totalScoreOf(attempt, meta);
        BigDecimal passScore = resolvePassScore(arrange, totalScore);
        r.setTotalScore(totalScore);
        r.setPassScore(passScore);
        // DER-03 / 04 §11.12：未出分一律 null，不能给 0
        r.setScore(graded ? attempt.getScore() : null);
        r.setPassed(graded ? passFlagOf(attempt) : null);
        r.setRankNo(graded ? attempt.getRankNo() : null);
        r.setSubmitAt(attempt.getSubmitAt());
        r.setUsedSeconds(usedSecondsOf(attempt));

        // 客观/主观分项：批阅中也要给（DER-03 的"先告诉学员已知部分"）
        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal objectiveFull = BigDecimal.ZERO;
        BigDecimal subjectivePending = BigDecimal.ZERO;
        for (ExamAttemptAnswer row : answers) {
            ExamQuestion ques = questions.get(row.getQuesId());
            PaperMeta m = meta.getOrDefault(row.getQuesId(), PaperMeta.ZERO);
            if (isObjective(ques == null ? null : ques.getType())) {
                objectiveScore = objectiveScore.add(nz(row.getScore()));
                objectiveFull = objectiveFull.add(m.score());
            } else if (row.getCorrectFlag() != null && row.getCorrectFlag() == FLAG_PENDING) {
                subjectivePending = subjectivePending.add(m.score());
            }
        }
        r.setObjectiveScore(objectiveScore);
        r.setObjectiveFullScore(objectiveFull);
        r.setSubjectivePending(subjectivePending);

        String block = AnswerViewPolicy.blockReason(arrange, advanced, attempt, graded);
        r.setAnswerDetailAvailable(block == null);
        r.setAnswerDetailBlockReason(block);

        r.setHighestScore(highestScoreOf(attempt));
        r.setHistory(historyOf(attempt));

        // 考核点得分与题型统计受开关控制：关掉时返回空数组而不是 null（前端数组渲染）
        boolean canViewPoint = AnswerViewPolicy.intOf(
                AnswerViewPolicy.childOf(advanced, "answerView"), "viewPointScore", 1) != 0;
        r.setPointScores(canViewPoint ? pointScoresOf(answers, questions, meta) : List.of());
        // 班内排名（AGENTS §7.2 口径）：分母是"同场已出分考生"，未出分一律不给排名
        RankingContext ranking = graded ? rankingContextOf(attempt) : null;
        List<StudentResultRespDtos.TypeStat> typeStats = typeStatsOf(attempt, answers, questions, meta);
        if (ranking != null) {
            withTypeRanks(typeStats, ranking);
        }
        r.setTypeStats(typeStats);
        // 用时排名：graded=false 或本人用时无效时为 null（给 0 会被展示成「最后一名」）
        r.setUsedTimeRank(ranking == null ? null : ranking.myUsedRank());
        r.setUsedTimeRankTotal(ranking == null ? null : ranking.rankDenominator());
        return r;
    }

    /**
     * 是否已出最终成绩。
     *
     * <p>三种"已出分"：status=3（判完）、status=4（作废，成绩无意义但状态终态）。
     * status=2 但已无待批阅题（历史数据里 status 没同步）也按已出分处理，
     * 否则学员会永远停在"批阅中"。
     */
    /**
     * 是否已出最终成绩。
     *
     * <p>判定顺序按契约 01 §4E 的口径：<b>"已交卷但存在待批阅题（correct_flag=3 或 status=2）"</b>
     * 就是批阅中。所以**待批阅标记优先于 status**——只看 status 会把
     * "判分收口了但主观题还在等人批"的脏数据误报成已完成，学员会看到 0 分或旧分。
     */
    private boolean isGraded(ExamAttempt attempt, List<ExamAttemptAnswer> answers) {
        if (attempt.getStatus() == null) {
            return false;
        }
        // 未交卷：既没成绩也谈不上批阅中
        if (attempt.getStatus() != STATUS_GRADING && attempt.getStatus() != STATUS_FINISHED
                && attempt.getStatus() != STATUS_INVALID) {
            return false;
        }
        // 只要还有一题待批阅，就不算出分（优先于 status）
        for (ExamAttemptAnswer row : answers) {
            if (row.getCorrectFlag() != null && row.getCorrectFlag() == FLAG_PENDING) {
                return false;
            }
        }
        if (attempt.getStatus() == STATUS_FINISHED || attempt.getStatus() == STATUS_INVALID) {
            return true;
        }
        // status=2 但已无待批阅题：历史数据里 status 没同步，按已出分处理，
        // 否则学员会永远停在"批阅中"
        return !answers.isEmpty();
    }

    /**
     * 考核点得分：按题聚合到考核点。
     *
     * <p>一个题可挂多个考核点，此时**每个考核点都记该题的满分与得分**——
     * 这与原平台"考核点得分表"的口径一致（同一题的分在多个考核点都可见），
     * 分摊反而会让满分合计与试卷总分对不上。
     */
    private List<StudentResultRespDtos.PointScore> pointScoresOf(List<ExamAttemptAnswer> answers,
                                                                  Map<String, ExamQuestion> questions,
                                                                  Map<String, PaperMeta> meta) {
        Map<String, StudentResultRespDtos.PointScore> byPoint = new LinkedHashMap<>();
        for (ExamAttemptAnswer row : answers) {
            ExamQuestion ques = questions.get(row.getQuesId());
            if (ques == null) {
                continue;
            }
            PaperMeta m = meta.getOrDefault(row.getQuesId(), PaperMeta.ZERO);
            for (String pointId : pointIdsOf(row.getQuesId())) {
                ExamPoint point = pointMapper.selectById(pointId);
                if (point == null) {
                    continue;
                }
                StudentResultRespDtos.PointScore ps = byPoint.computeIfAbsent(pointId, k -> {
                    StudentResultRespDtos.PointScore p = new StudentResultRespDtos.PointScore();
                    p.setPointId(k);
                    p.setPointName(point.getName());
                    p.setTotal(BigDecimal.ZERO);
                    p.setScore(BigDecimal.ZERO);
                    return p;
                });
                ps.setTotal(ps.getTotal().add(m.score()));
                ps.setScore(ps.getScore().add(nz(row.getScore())));
            }
        }
        for (StudentResultRespDtos.PointScore ps : byPoint.values()) {
            ps.setRate(rate(ps.getScore(), ps.getTotal()));
        }
        return new ArrayList<>(byPoint.values());
    }

    /**
     * 分题型统计。
     *
     * <p>组合题按**父题聚合**：父题算 1 道，答对指全部子题都对。
     * 按子题平铺会让"5 道子题"变成 5 道题，与入场页的题数口径对不上。
     */
    private List<StudentResultRespDtos.TypeStat> typeStatsOf(ExamAttempt attempt,
                                                              List<ExamAttemptAnswer> answers,
                                                              Map<String, ExamQuestion> questions,
                                                              Map<String, PaperMeta> meta) {
        Map<String, ExamAttemptAnswer> byQues = new LinkedHashMap<>();
        for (ExamAttemptAnswer row : answers) {
            byQues.put(row.getQuesId(), row);
        }
        Map<Integer, int[]> counter = typeCounter(snapshotRels(attempt), questions, byQues);
        List<StudentResultRespDtos.TypeStat> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : counter.entrySet()) {
            StudentResultRespDtos.TypeStat stat = new StudentResultRespDtos.TypeStat();
            stat.setType(e.getKey());
            stat.setTypeName(typeName(e.getKey()));
            stat.setTotal(e.getValue()[0]);
            stat.setCorrect(e.getValue()[1]);
            stat.setRate(rate(BigDecimal.valueOf(e.getValue()[1]), BigDecimal.valueOf(e.getValue()[0])));
            out.add(stat);
        }
        return out;
    }

    /**
     * 题型计数：type → [总题数, 答对数]。
     *
     * <p>抽出来是为了让**本人与同场考生用同一份计算**：若两处各写一遍，
     * 本人页面显示的"正确率"与排名所依据的正确率会慢慢分叉，
     * 出现"我 100% 却排第 2"这种没人能解释的现象。
     */
    private Map<Integer, int[]> typeCounter(List<ExamPaperQuestion> rels, Map<String, ExamQuestion> questions,
                                            Map<String, ExamAttemptAnswer> byQues) {
        Map<Integer, int[]> counter = new TreeMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            Integer type = ques.getType();
            int[] c = counter.computeIfAbsent(type == null ? 0 : type, k -> new int[] { 0, 0 });
            c[0]++;
            if (type != null && type == ExamQuestion.TYPE_COMPOSITE) {
                // 组合题：所有子题都判对才算这道组合题答对
                if (allSubCorrect(questions, byQues, ques.getId())) {
                    c[1]++;
                }
            } else {
                ExamAttemptAnswer row = byQues.get(ques.getId());
                if (row != null && row.getCorrectFlag() != null && row.getCorrectFlag() == 1) {
                    c[1]++;
                }
            }
        }
        return counter;
    }

    /** 把题型排名写进已算好的统计行（rate 已由 typeStatsOf 填好，排名依据就是它）。 */
    private void withTypeRanks(List<StudentResultRespDtos.TypeStat> stats, RankingContext ranking) {
        for (StudentResultRespDtos.TypeStat stat : stats) {
            stat.setRank(ranking.typeRank(stat.getType(), stat.getRate()));
            stat.setRankTotal(ranking.typeRankTotal());
        }
    }

    /**
     * 用于排名的用时（秒）；数据不足时返回 null。
     *
     * <p>与 {@link #usedSecondsOf} 的区别：那个返回 int、异常时给 0，只服务于展示；
     * 排名必须能表达"无效"。**不能把无效当成 0** —— 0 秒会直接排到第一名（AGENTS §7.2）。
     *
     * <p>无效判定：既没有 {@code duration_sec}、也没有 {@code start_at}（算不出真实用时），
     * 或算出来 {@code <= 0}（异常数据）。这两种一律不参与用时排名。
     */
    private Integer usedSecondsOfRank(ExamAttempt attempt) {
        boolean hasDuration = attempt.getDurationSec() != null && attempt.getDurationSec() > 0;
        if (!hasDuration && attempt.getStartAt() == null) {
            return null;
        }
        int used = usedSecondsOf(attempt);
        return used > 0 ? used : null;
    }

    /**
     * 构建班内排名上下文（AGENTS §7.2）：同场**已出分**（status=3）考生的
     * 各题型正确率与有效用时。
     *
     * <p><b>性能</b>：全部批量预取，SQL 条数与考生人数无关（约 5 条：答卷 1、作答 1、
     * 每快照 1、题目 1、子题 1）。若按答卷逐条查快照/题目，50 人的场次会是 150+ 条 SQL，
     * 结果详情接口必超时。
     *
     * <p>作废卷（status=4）不参与：04 §11B.4 明确 invalid 不计入统计与通过率。
     */
    private RankingContext rankingContextOf(ExamAttempt current) {
        List<ExamAttempt> graded = new ArrayList<>();
        for (ExamAttempt a : attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, current.getArrangeId()))) {
            if (a.getStatus() != null && a.getStatus() == STATUS_FINISHED) {
                graded.add(a);
            }
        }
        if (graded.isEmpty()) {
            return null;
        }
        // 一次批量取所有答卷的作答（避免每人为一次查询）
        List<String> attemptIds = new ArrayList<>();
        for (ExamAttempt a : graded) {
            attemptIds.add(a.getId());
        }
        Map<String, Map<String, ExamAttemptAnswer>> answersByAttempt = new HashMap<>();
        for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .in(ExamAttemptAnswer::getAttemptId, attemptIds))) {
            answersByAttempt.computeIfAbsent(row.getAttemptId(), k -> new LinkedHashMap<>())
                    .put(row.getQuesId(), row);
        }
        // 按快照分组解析卷面（同一场次通常只有一个快照，多人共享一份）
        Map<String, SnapshotStructure> structures = structuresOf(graded);

        List<PeerStat> peers = new ArrayList<>();
        for (ExamAttempt a : graded) {
            SnapshotStructure st = structures.get(snapshotKeyOf(a));
            Map<Integer, BigDecimal> rates = new HashMap<>();
            if (st != null) {
                Map<String, ExamAttemptAnswer> byQues = answersByAttempt.getOrDefault(a.getId(), Map.of());
                for (Map.Entry<Integer, int[]> e : typeCounter(st.rels(), st.questions(), byQues).entrySet()) {
                    rates.put(e.getKey(),
                            rate(BigDecimal.valueOf(e.getValue()[1]), BigDecimal.valueOf(e.getValue()[0])));
                }
            }
            peers.add(new PeerStat(rates, usedSecondsOfRank(a)));
        }

        Integer myUsed = usedSecondsOfRank(current);
        Integer myRank = null;
        int participants = 0;
        if (myUsed != null) {
            int faster = 0;
            for (PeerStat p : peers) {
                if (p.usedSeconds() == null) {
                    continue;
                }
                participants++;
                // 严格更快才算"排在我前面"，等时并列 → 同名次（1,1,3）
                if (p.usedSeconds() < myUsed) {
                    faster++;
                }
            }
            myRank = faster + 1;
        }
        return new RankingContext(peers, myRank, participants);
    }

    /** 快照分组键：无快照的历史答卷按试卷归并，避免每条答卷各查一次。 */
    private String snapshotKeyOf(ExamAttempt attempt) {
        return StringUtils.hasText(attempt.getSnapshotId())
                ? attempt.getSnapshotId()
                : "paper:" + (attempt.getPaperId() == null ? "" : attempt.getPaperId());
    }

    /**
     * 批量构建各快照的卷面结构。
     *
     * <p>先去重快照（多人共享），再**一次**批量取全部题目与组合题子题，
     * 使 SQL 条数与考生人数无关。
     */
    private Map<String, SnapshotStructure> structuresOf(List<ExamAttempt> attempts) {
        Map<String, List<ExamPaperQuestion>> relsBySnapshot = new LinkedHashMap<>();
        for (ExamAttempt a : attempts) {
            String key = snapshotKeyOf(a);
            if (relsBySnapshot.containsKey(key)) {
                continue;
            }
            List<ExamPaperQuestion> rels = List.of();
            if (StringUtils.hasText(a.getSnapshotId())) {
                ExamPaperSnapshot snap = snapshotMapper.selectById(a.getSnapshotId());
                if (snap != null && StringUtils.hasText(snap.getSnapshotJson())) {
                    rels = parseSnapshotRels(snap.getSnapshotJson());
                }
            }
            // 快照缺失（历史数据）才回退实时试卷；与 snapshotRels 的兜底口径一致
            if (rels.isEmpty() && StringUtils.hasText(a.getPaperId())) {
                rels = paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, a.getPaperId())
                        .orderByAsc(ExamPaperQuestion::getSectionIndex)
                        .orderByAsc(ExamPaperQuestion::getOrderIndex));
            }
            relsBySnapshot.put(key, rels);
        }

        // 所有快照的题目 id 合并成一次查询
        Set<String> quesIds = new LinkedHashSet<>();
        for (List<ExamPaperQuestion> rels : relsBySnapshot.values()) {
            for (ExamPaperQuestion rel : rels) {
                if (StringUtils.hasText(rel.getQuesId())) {
                    quesIds.add(rel.getQuesId());
                }
            }
        }
        Map<String, ExamQuestion> questions = new HashMap<>();
        if (!quesIds.isEmpty()) {
            List<String> compositeIds = new ArrayList<>();
            for (ExamQuestion q : questionMapper.selectBatchIds(quesIds)) {
                questions.put(q.getId(), q);
                if (q.getType() != null && q.getType() == ExamQuestion.TYPE_COMPOSITE) {
                    compositeIds.add(q.getId());
                }
            }
            // 子题不在卷面 rels 里，必须补查，否则组合题会被当成"没答对"
            if (!compositeIds.isEmpty()) {
                for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                        .in(ExamQuestion::getParentId, compositeIds))) {
                    questions.put(sub.getId(), sub);
                }
            }
        }
        Map<String, SnapshotStructure> out = new HashMap<>();
        for (Map.Entry<String, List<ExamPaperQuestion>> e : relsBySnapshot.entrySet()) {
            out.put(e.getKey(), new SnapshotStructure(e.getValue(), questions));
        }
        return out;
    }

    /** 单个同场考生的排名输入：各题型正确率 + 有效用时。 */
    private record PeerStat(Map<Integer, BigDecimal> typeRates, Integer usedSeconds) {
    }

    /** 一份快照的卷面：题目关联 + 题目实体（含组合题子题）。 */
    private record SnapshotStructure(List<ExamPaperQuestion> rels, Map<String, ExamQuestion> questions) {
    }

    /**
     * 班内排名上下文。
     *
     * <p>并列口径统一为「竞争排名」：名次 = 1 + 严格比我好的人数。
     * 两人同为 100% 就都是第 1，下一个是第 3（1,1,3），与 AGENTS §7.2 一致。
     *
     * @param peers              同场已出分考生（含本人；本人不会"严格优于自己"，不影响名次）
     * @param myUsedRank         本人用时排名；用时无效时为 null
     * @param usedTimeParticipants 参与用时排名的人数（分母）
     */
    private record RankingContext(List<PeerStat> peers, Integer myUsedRank, int participants) {
        /** 题型排名的分母：同场已出分考生数，与总分排名同一口径。 */
        int typeRankTotal() {
            return peers.size();
        }

        /**
         * 本人某题型的名次（按正确率降序）。
         *
         * <p>只把「有该题型且正确率严格更高」的人算作排在我前面：
         * 没遇到该题型的人无法比较，不能被当成"比我好"，
         * 否则随机卷下每个人的名次都会被凭空推后。
         */
        Integer typeRank(Integer type, BigDecimal myRate) {
            if (myRate == null) {
                return null;
            }
            int better = 0;
            for (PeerStat p : peers) {
                BigDecimal r = p.typeRates().get(type);
                if (r != null && r.compareTo(myRate) > 0) {
                    better++;
                }
            }
            return better + 1;
        }

        /** 用时排名分母；本人用时无效时不给分母（避免出现"排名无、分母有"的矛盾组合）。 */
        Integer rankDenominator() {
            return myUsedRank == null ? null : participants;
        }
    }

    private boolean allSubCorrect(Map<String, ExamQuestion> questions, Map<String, ExamAttemptAnswer> byQues,
                                  String parentId) {
        boolean hasSub = false;
        for (ExamQuestion sub : questions.values()) {
            if (!parentId.equals(sub.getParentId())) {
                continue;
            }
            hasSub = true;
            ExamAttemptAnswer row = byQues.get(sub.getId());
            if (row == null || row.getCorrectFlag() == null || row.getCorrectFlag() != 1) {
                return false;
            }
        }
        return hasSub;
    }

    /** 历史：该学员在该场考试的全部作答，按提交时间倒序（04 §11.12）。 */
    private List<StudentResultRespDtos.HistoryRow> historyOf(ExamAttempt attempt) {
        List<ExamAttempt> all = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, attempt.getArrangeId())
                .eq(ExamAttempt::getUserId, attempt.getUserId()));
        List<ExamAttempt> submitted = new ArrayList<>();
        for (ExamAttempt a : all) {
            if (a.getSubmitAt() != null) {
                submitted.add(a);
            }
        }
        submitted.sort(Comparator.comparing(ExamAttempt::getSubmitAt).reversed());
        List<StudentResultRespDtos.HistoryRow> rows = new ArrayList<>();
        for (ExamAttempt a : submitted) {
            StudentResultRespDtos.HistoryRow h = new StudentResultRespDtos.HistoryRow();
            h.setAttemptId(a.getId());
            h.setScore(a.getStatus() != null && a.getStatus() == STATUS_FINISHED ? a.getScore() : null);
            h.setPassed(a.getStatus() != null && a.getStatus() == STATUS_FINISHED ? passFlagOf(a) : null);
            h.setSubmitAt(a.getSubmitAt());
            rows.add(h);
        }
        return rows;
    }

    /** 历史最高分：只取已完成（status=3）的卷，作废/批阅中不算。 */
    private BigDecimal highestScoreOf(ExamAttempt attempt) {
        BigDecimal highest = null;
        for (ExamAttempt a : attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, attempt.getArrangeId())
                .eq(ExamAttempt::getUserId, attempt.getUserId()))) {
            if (a.getStatus() == null || a.getStatus() != STATUS_FINISHED || a.getScore() == null) {
                continue;
            }
            if (highest == null || a.getScore().compareTo(highest) > 0) {
                highest = a.getScore();
            }
        }
        return highest;
    }

    // ---------------- §11.13 答卷详情 ----------------

    @Override
    public StudentResultRespDtos.PaperDetail paperDetail(String userId, String attemptId, Integer wrongOnly) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        Map<String, Object> advanced = readJson(arrange == null ? null : arrange.getAdvancedSettings());
        List<ExamAttemptAnswer> answers = answersOf(attemptId);
        boolean graded = isGraded(attempt, answers);

        // 可见性：不可看时 40055 + 原因（与入场页 tooltip 同源）
        String block = AnswerViewPolicy.blockReason(arrange, advanced, attempt, graded);
        if (block != null) {
            throw new BizException(ERR_DETAIL_BLOCKED, block);
        }
        boolean showAnswer = AnswerViewPolicy.viewAnswer(advanced);
        boolean showAnalysis = AnswerViewPolicy.viewAnalysis(advanced);
        boolean onlyWrong = wrongOnly != null ? wrongOnly == 1 : AnswerViewPolicy.wrongOnlyDefault(advanced);

        Map<String, ExamQuestion> questions = questionsOf(attempt);
        Map<String, ExamAttemptAnswer> byQues = new LinkedHashMap<>();
        for (ExamAttemptAnswer row : answers) {
            byQues.put(row.getQuesId(), row);
        }
        Map<String, List<ExamQuestionItem>> items = itemsOf(questions.keySet());

        StudentResultRespDtos.PaperDetail detail = new StudentResultRespDtos.PaperDetail();
        detail.setAttemptId(attempt.getId());
        detail.setName(arrange == null ? "" : arrange.getName());
        detail.setTotalScore(totalScoreOf(attempt, paperMetaOf(attempt)));
        detail.setScore(attempt.getScore());
        detail.setPassScore(resolvePassScore(arrange, detail.getTotalScore()));
        detail.setPassed(passFlagOf(attempt));
        detail.setSubmitAt(attempt.getSubmitAt());
        detail.setSignatureUrl(signatureUrlOf(attempt, advanced));

        // 按大题分组，组内保留卷面顺序
        Map<Integer, List<StudentResultRespDtos.Card>> grouped = new TreeMap<>();
        for (ExamPaperQuestion rel : snapshotRels(attempt)) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            int sectionIndex = rel.getSectionIndex() == null ? 0 : rel.getSectionIndex();
            StudentResultRespDtos.Card card = toCard(rel, ques, byQues, items, showAnswer, showAnalysis);
            // 只看错题：本题（组合题看子题）没有错就不进这个分组——
            // 真过滤，连大题标题都不会出现（01 §S7）
            if (onlyWrong && !hasWrong(card, ques, byQues)) {
                continue;
            }
            grouped.computeIfAbsent(sectionIndex, k -> new ArrayList<>()).add(card);
        }

        List<StudentResultRespDtos.Section> sections = new ArrayList<>();
        for (Map.Entry<Integer, List<StudentResultRespDtos.Card>> e : grouped.entrySet()) {
            StudentResultRespDtos.Section section = new StudentResultRespDtos.Section();
            section.setSectionIndex(e.getKey());
            section.setSectionName(sectionName(e.getKey(), e.getValue()));
            section.setCards(e.getValue());
            sections.add(section);
        }
        detail.setSections(sections);
        return detail;
    }

    /** 该题卡是否含错：单个题看 correctFlag，组合题看任一子题错。 */
    private boolean hasWrong(StudentResultRespDtos.Card card, ExamQuestion ques,
                              Map<String, ExamAttemptAnswer> byQues) {
        Integer type = ques.getType();
        if (type != null && type == ExamQuestion.TYPE_COMPOSITE) {
            if (card.getSubQuestions() == null) {
                return false;
            }
            for (StudentResultRespDtos.Card sub : card.getSubQuestions()) {
                if (sub.getCorrectFlag() == null || sub.getCorrectFlag() != 1) {
                    return true;
                }
            }
            return false;
        }
        ExamAttemptAnswer row = byQues.get(ques.getId());
        // 未判分（correctFlag=null）不算错：把"没判"当"错"会让批阅中的答卷满屏红
        return row != null && row.getCorrectFlag() != null && row.getCorrectFlag() != 1;
    }

    private StudentResultRespDtos.Card toCard(ExamPaperQuestion rel, ExamQuestion ques,
                                               Map<String, ExamAttemptAnswer> byQues,
                                               Map<String, List<ExamQuestionItem>> items,
                                               boolean showAnswer, boolean showAnalysis) {
        ExamAttemptAnswer row = byQues.get(ques.getId());
        StudentResultRespDtos.Card card = new StudentResultRespDtos.Card();
        card.setQuesId(ques.getId());
        card.setType(ques.getType());
        card.setScore(rel.getScore());
        card.setContent(ques.getContent());
        // 未判分给 null（不是 0）：前端显示"未判分"而不是"0 分"
        card.setEarnedScore(row == null ? null : row.getScore());
        card.setCorrectFlag(row == null ? null : row.getCorrectFlag());
        card.setUserAnswer(row == null ? List.of() : readAnswerIds(row.getAnswerJson()));
        card.setPoints(pointNamesOf(ques.getId()));

        Integer type = ques.getType();
        if (type != null && (type == ExamQuestion.TYPE_SINGLE
                || type == ExamQuestion.TYPE_MULTI || type == ExamQuestion.TYPE_JUDGE)) {
            card.setOptions(optionsOf(ques, items.get(ques.getId()), card.getUserAnswer(), showAnswer));
        }
        if (showAnswer) {
            card.setAnswerContent(correctAnswerOf(ques, items.get(ques.getId())));
        }
        if (showAnalysis) {
            card.setAnalysis(StringUtils.hasText(ques.getExplainText()) ? ques.getExplainText() : "暂无试题解析");
        }
        if (type != null && type == ExamQuestion.TYPE_ANSWER) {
            card.setKeywords(keywordsOf(ques.getAnswerKeywords()));
        }
        if (type != null && type == ExamQuestion.TYPE_COMPOSITE) {
            List<StudentResultRespDtos.Card> subs = new ArrayList<>();
            Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
            for (ExamQuestion sub : questionsOf(ques.getId())) {
                ExamPaperQuestion subRel = new ExamPaperQuestion();
                subRel.setQuesId(sub.getId());
                subRel.setScore(subScores.getOrDefault(sub.getId(), BigDecimal.ZERO));
                subs.add(toCard(subRel, sub, byQues, items, showAnswer, showAnalysis));
            }
            card.setSubQuestions(subs);
        }
        return card;
    }

    private List<StudentResultRespDtos.Option> optionsOf(ExamQuestion ques, List<ExamQuestionItem> items,
                                                          List<String> picked, boolean showAnswer) {
        List<StudentResultRespDtos.Option> out = new ArrayList<>();
        if (ques.getType() != null && ques.getType() == ExamQuestion.TYPE_JUDGE) {
            // 判断题没有选项行：按两个虚拟选项渲染，correct 由 judgeAnswer 决定（0 正确 / 1 错误）
            boolean firstIsCorrect = ques.getJudgeAnswer() == null || ques.getJudgeAnswer() == 0;
            out.add(judgeOption(ques.getId() + "-judge-0",
                    StringUtils.hasText(ques.getJudgeCorrectOptionContent())
                            ? ques.getJudgeCorrectOptionContent() : "正确",
                    1, firstIsCorrect, showAnswer, picked));
            out.add(judgeOption(ques.getId() + "-judge-1",
                    StringUtils.hasText(ques.getJudgeWrongOptionContent())
                            ? ques.getJudgeWrongOptionContent() : "错误",
                    2, !firstIsCorrect, showAnswer, picked));
            return out;
        }
        if (items == null) {
            return out;
        }
        int i = 0;
        for (ExamQuestionItem item : items) {
            i++;
            StudentResultRespDtos.Option opt = new StudentResultRespDtos.Option();
            opt.setItemId(item.getId());
            opt.setContent(item.getItemContent());
            opt.setOrderIndex(item.getOrderIndex() == null ? i : item.getOrderIndex());
            // correct 仅 showAnswer 时下发；picked 始终下发（学员要看自己选了什么）
            if (showAnswer) {
                opt.setCorrect(item.getAnswer() != null && item.getAnswer() == 1 ? 1 : 0);
            }
            opt.setPicked(picked != null && picked.contains(item.getId()) ? 1 : 0);
            out.add(opt);
        }
        return out;
    }

    /**
     * 判断题虚拟选项。
     *
     * <p>`showAnswer=false` 时 `correct` 必须是 **null（字段不下发）**，不能给 0——
     * 给 0 等于告诉前端"这个选项是错的"，未开放查看答案的开关就被绕过了。
     */
    private StudentResultRespDtos.Option judgeOption(String itemId, String content, int orderIndex,
                                                      boolean isCorrect, boolean showAnswer,
                                                      List<String> picked) {
        StudentResultRespDtos.Option opt = new StudentResultRespDtos.Option();
        opt.setItemId(itemId);
        opt.setContent(content);
        opt.setOrderIndex(orderIndex);
        if (showAnswer) {
            opt.setCorrect(isCorrect ? 1 : 0);
        }
        opt.setPicked(picked != null && picked.contains(itemId) ? 1 : 0);
        return opt;
    }

    // ---------------- 内部：读取 ----------------

    private ExamAttempt requireOwnAttempt(String userId, String attemptId) {
        if (!StringUtils.hasText(attemptId)) {
            throw new BizException(40001, "答卷 id 不能为空");
        }
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(attempt.getUserId(), userId)) {
            throw new BizException(ERR_NOT_FOUND, "答卷不存在或已删除");
        }
        return attempt;
    }

    private List<ExamAttemptAnswer> answersOf(String attemptId) {
        return answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId));
    }

    private List<String> pointIdsOf(String quesId) {
        List<String> ids = new ArrayList<>();
        for (ExamQuestionPoint rel : questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, quesId)
                .orderByAsc(ExamQuestionPoint::getOrderIndex))) {
            if (StringUtils.hasText(rel.getPointId())) {
                ids.add(rel.getPointId());
            }
        }
        return ids;
    }

    private List<String> pointNamesOf(String quesId) {
        List<String> names = new ArrayList<>();
        for (String pointId : pointIdsOf(quesId)) {
            ExamPoint p = pointMapper.selectById(pointId);
            if (p != null && StringUtils.hasText(p.getName())) {
                names.add(p.getName());
            }
        }
        return names;
    }

    /** 组合题子题（按 order_index）。 */
    private List<ExamQuestion> questionsOf(String parentId) {
        return questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, parentId)
                .orderByAsc(ExamQuestion::getOrderIndex));
    }

    private Map<String, List<ExamQuestionItem>> itemsOf(Set<String> quesIds) {
        Map<String, List<ExamQuestionItem>> map = new HashMap<>();
        if (quesIds == null || quesIds.isEmpty()) {
            return map;
        }
        for (ExamQuestionItem item : itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .in(ExamQuestionItem::getQuesId, quesIds)
                .orderByAsc(ExamQuestionItem::getOrderIndex))) {
            map.computeIfAbsent(item.getQuesId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private String signatureUrlOf(ExamAttempt attempt, Map<String, Object> advanced) {
        // signatureInAnswerDetail=1 才展示（01 §4D）
        if (AnswerViewPolicy.intOf(advanced, "signatureInAnswerDetail", 0) != 1) {
            return null;
        }
        ExamArrangeSignature sig = signatureMapper.selectOne(Wrappers.<ExamArrangeSignature>lambdaQuery()
                .eq(ExamArrangeSignature::getAttemptId, attempt.getId())
                .orderByDesc(ExamArrangeSignature::getSignedAt)
                .last("limit 1"));
        return sig == null ? null : sig.getSignatureUrl();
    }

    private Map<String, ExamQuestion> questionsOf(ExamAttempt attempt) {
        Map<String, ExamQuestion> map = new HashMap<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        if (rels.isEmpty()) {
            return map;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamPaperQuestion rel : rels) {
            if (StringUtils.hasText(rel.getQuesId())) {
                ids.add(rel.getQuesId());
            }
        }
        List<ExamQuestion> tops = questionMapper.selectBatchIds(ids);
        List<String> compositeIds = new ArrayList<>();
        for (ExamQuestion q : tops) {
            map.put(q.getId(), q);
            if (q.getType() != null && q.getType() == ExamQuestion.TYPE_COMPOSITE) {
                compositeIds.add(q.getId());
            }
        }
        if (!compositeIds.isEmpty()) {
            for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                    .in(ExamQuestion::getParentId, compositeIds))) {
                map.put(sub.getId(), sub);
            }
        }
        return map;
    }

    // ---------------- 内部：快照与配分 ----------------

    private List<ExamPaperQuestion> snapshotRels(ExamAttempt attempt) {
        if (StringUtils.hasText(attempt.getSnapshotId())) {
            ExamPaperSnapshot snap = snapshotMapper.selectById(attempt.getSnapshotId());
            if (snap != null && StringUtils.hasText(snap.getSnapshotJson())) {
                List<ExamPaperQuestion> rels = parseSnapshotRels(snap.getSnapshotJson());
                if (!rels.isEmpty()) {
                    return rels;
                }
            }
        }
        if (!StringUtils.hasText(attempt.getPaperId())) {
            return List.of();
        }
        log.warn("答卷缺少可用快照，回退实时试卷 attemptId={}", attempt.getId());
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, attempt.getPaperId())
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    private List<ExamPaperQuestion> parseSnapshotRels(String snapshotJson) {
        List<ExamPaperQuestion> rels = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(snapshotJson);
            JsonNode qs = root.path("questions");
            if (!qs.isArray()) {
                return rels;
            }
            for (JsonNode n : qs) {
                ExamPaperQuestion rel = new ExamPaperQuestion();
                rel.setQuesId(n.path("quesId").asText(""));
                rel.setSectionIndex(n.path("sectionIndex").asInt(0));
                rel.setOrderIndex(n.path("orderIndex").asInt(0));
                rel.setScore(decimalOf(n, "score"));
                rel.setMissScore(decimalOf(n, "missScore"));
                rel.setMissScoreType(n.path("missScoreType").asInt(0));
                rel.setSubScores(n.hasNonNull("subScores") ? n.get("subScores").toString() : null);
                rels.add(rel);
            }
        } catch (Exception e) {
            log.warn("解析试卷快照失败: {}", e.getMessage());
        }
        return rels;
    }

    private Map<String, PaperMeta> paperMetaOf(ExamAttempt attempt) {
        Map<String, PaperMeta> map = new HashMap<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        Map<String, ExamQuestion> questions = questionsOf(attempt);
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (ques.getType() != null && ques.getType() == ExamQuestion.TYPE_COMPOSITE) {
                Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
                for (ExamQuestion sub : questions.values()) {
                    if (rel.getQuesId().equals(sub.getParentId())) {
                        map.put(sub.getId(), new PaperMeta(subScores.getOrDefault(sub.getId(), BigDecimal.ZERO)));
                    }
                }
            } else {
                map.put(ques.getId(), new PaperMeta(rel.getScore()));
            }
        }
        return map;
    }

    private BigDecimal totalScoreOf(ExamAttempt attempt, Map<String, PaperMeta> meta) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperQuestion rel : snapshotRels(attempt)) {
            if (rel.getScore() != null) {
                total = total.add(rel.getScore());
            }
        }
        return total;
    }

    private Map<String, BigDecimal> subScoresOf(String raw) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (!StringUtils.hasText(raw)) {
            return map;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String quesId = n.path("quesId").asText("");
                    if (!quesId.isEmpty()) {
                        map.put(quesId, decimalOf(n, "score"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析组合题子题分值失败: {}", e.getMessage());
        }
        return map;
    }

    // ---------------- 内部：小工具 ----------------

    private boolean isObjective(Integer type) {
        return type != null && (type == ExamQuestion.TYPE_SINGLE
                || type == ExamQuestion.TYPE_MULTI || type == ExamQuestion.TYPE_JUDGE);
    }

    private BigDecimal resolvePassScore(ExamArrange arrange, BigDecimal totalScore) {
        if (arrange == null) {
            return BigDecimal.ZERO;
        }
        if (arrange.getPassScoreType() != null && arrange.getPassScoreType() == 2) {
            BigDecimal ratio = arrange.getPassRatio() == null ? BigDecimal.ZERO : arrange.getPassRatio();
            return (totalScore == null ? BigDecimal.ZERO : totalScore)
                    .multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        }
        return arrange.getPassScore() == null ? BigDecimal.ZERO : arrange.getPassScore();
    }

    private Integer passFlagOf(ExamAttempt attempt) {
        if (attempt == null || attempt.getPassStatus() == null || attempt.getPassStatus() < 0) {
            return null;
        }
        return attempt.getPassStatus();
    }

    private int usedSecondsOf(ExamAttempt attempt) {
        if (attempt.getDurationSec() != null && attempt.getDurationSec() > 0) {
            return attempt.getDurationSec();
        }
        if (attempt.getStartAt() == null) {
            return 0;
        }
        LocalDateTime end = attempt.getSubmitAt() == null ? LocalDateTime.now() : attempt.getSubmitAt();
        return (int) Math.max(0, Duration.between(attempt.getStartAt(), end).getSeconds());
    }

    private BigDecimal rate(BigDecimal score, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return score.divide(total, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "未知";
    }

    private String sectionName(int sectionIndex, List<StudentResultRespDtos.Card> cards) {
        Integer type = cards.isEmpty() ? null : cards.get(0).getType();
        String typeName = type == null || type < 0 || type >= TYPE_NAMES.length ? "题目" : TYPE_NAMES[type];
        String prefix = sectionIndex >= 0 && sectionIndex < CN_NUM.length
                ? CN_NUM[sectionIndex] : String.valueOf(sectionIndex + 1);
        return prefix + "、" + typeName;
    }

    /** 标准答案展示文本（选项型转字母，其余原样）。 */
    private String correctAnswerOf(ExamQuestion ques, List<ExamQuestionItem> items) {
        Integer type = ques.getType();
        if (type != null && (type == ExamQuestion.TYPE_SINGLE || type == ExamQuestion.TYPE_MULTI)) {
            StringBuilder sb = new StringBuilder();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getAnswer() != null && items.get(i).getAnswer() == 1) {
                        sb.append((char) ('A' + i));
                    }
                }
            }
            return sb.toString();
        }
        if (type != null && type == ExamQuestion.TYPE_JUDGE) {
            return ques.getJudgeAnswer() == null || ques.getJudgeAnswer() == 0 ? "正确" : "错误";
        }
        return ques.getAnswerContent();
    }

    private List<String> keywordsOf(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String kw = n.path("keyword").asText("");
                    if (!kw.isEmpty()) {
                        out.add(kw);
                    }
                }
            }
        } catch (Exception ignored) {
            // 关键词读不出来不影响答题回显
        }
        return out;
    }

    private List<String> readAnswerIds(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            JsonNode arr = node.isObject()
                    ? (node.has("answer") ? node.get("answer") : node.get("selected"))
                    : node;
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isNull()) {
                        continue;
                    }
                    String s = n.isNumber() ? String.valueOf(n.asInt()) : n.asText("");
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            } else if (arr != null && !arr.isNull()) {
                String s = arr.asText("");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            if (text.length() <= 26 && text.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
                for (char c : text.toCharArray()) {
                    out.add(String.valueOf(c));
                }
            } else {
                out.add(text);
            }
        }
        return out;
    }

    private Map<String, Object> readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(raw, JSON.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    private record PaperMeta(BigDecimal score) {
        static final PaperMeta ZERO = new PaperMeta(BigDecimal.ZERO);
    }
}
