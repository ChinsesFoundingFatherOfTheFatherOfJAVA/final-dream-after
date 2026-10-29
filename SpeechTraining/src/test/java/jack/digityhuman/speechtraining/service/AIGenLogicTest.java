package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.req.AiGenDtos;
import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI生成话术闭环（v2.0）纯逻辑回归：导入编排 + 素材拼接（不触 Dify/DB 的部分）。
 */
class AIGenLogicTest {

    /** 与生产实现同一编排逻辑（编排是无状态纯函数，独立实现断言预期结构）。 */
    private final jack.digityhuman.speechtraining.service.impl.AIGenServiceImpl service =
            new jack.digityhuman.speechtraining.service.impl.AIGenServiceImpl(null, null);

    @Test
    void importScriptBuildsTrainerFirstFlow() {
        // 陪练者先说：大节点 type=2"陪练者问学员说"；开始→各轮→结束直线边
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("trainer");
        req.setRounds(List.of(round(1), round(2)));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes()).hasSize(4); // 开始+2轮+结束
        assertThat(resp.getNodes().get(0).getNodeType()).isEqualTo(1);
        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(2);
        assertThat(resp.getNodes().get(1).getNodeName()).isEqualTo("陪练者问学员说");
        assertThat(resp.getNodes().get(1).getTrainerQuestion()).isEqualTo("Q1");
        assertThat(resp.getNodes().get(1).getStandardScript()).isEqualTo("A1");
        assertThat(resp.getNodes().get(1).getKeyPoints()).hasSize(1);
        assertThat(resp.getNodes().get(1).getKeyPoints().get(0).getId()).isEqualTo("kp-0-0");
        assertThat(resp.getNodes().get(3).getNodeType()).isEqualTo(4);
        // 布局：BIG_NODE_STEP=260 节奏
        assertThat(resp.getNodes().get(2).getPositionY()).isEqualTo(100 + 2 * 260);
        assertThat(resp.getEdges()).hasSize(3);
        assertThat(resp.getEdges().get(0).getSource()).isEqualTo(0);
        assertThat(resp.getEdges().get(0).getTarget()).isEqualTo(1);
        assertThat(resp.getEdges().get(2).getTarget()).isEqualTo(3);
    }

    @Test
    void importScriptStudentFirstUsesType3() {
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("student");
        req.setRounds(List.of(round(1)));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(3);
        assertThat(resp.getNodes().get(1).getNodeName()).isEqualTo("学员问陪练者说");
    }

    @Test
    void importScriptUsesSpeakerOrderOverLedMarker() {
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("student");
        AiGenDtos.RoundItem first = round(1);
        first.setSpeakerOrder("student");
        first.setAnalysis("学员主导型对话：模型误标，但顺序字段优先");
        AiGenDtos.RoundItem second = round(2);
        second.setSpeakerOrder("trainer");
        req.setRounds(List.of(first, second));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(3);
        assertThat(resp.getNodes().get(2).getNodeType()).isEqualTo(2);
    }

    @Test
    void importScriptKeepsStudentLedRoundsAsType3() {
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("student");
        AiGenDtos.RoundItem first = round(1);
        first.setAnalysis("学员主导型对话：学员持续发问");
        req.setRounds(List.of(first, round(2)));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(3);
        assertThat(resp.getNodes().get(2).getNodeType()).isEqualTo(3);
    }

    @Test
    void importScriptInsertsNarrationNodeWhenPresent() {
        // v1.2：narration 非空 → 开始节点与首个大节点之间插入 nodeType=5 旁白节点
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("student");
        req.setNarration("你是销售专员张伟，刘总刚走进展厅，请主动上前开场");
        req.setRounds(List.of(round(1), round(2)));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes()).hasSize(5); // 开始+旁白+2轮+结束
        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(5);
        assertThat(resp.getNodes().get(1).getNodeName()).isEqualTo("旁白");
        assertThat(resp.getNodes().get(1).getNarration()).contains("张伟");
        assertThat(resp.getNodes().get(2).getNodeType()).isEqualTo(3); // 第 1 轮学员先说
        assertThat(resp.getNodes().get(3).getNodeType()).isEqualTo(2); // 第 2 轮起陪练者先说
        assertThat(resp.getNodes().get(2).getPositionY()).isEqualTo(100 + 2 * 260);
        assertThat(resp.getEdges()).hasSize(4);
        // 边链：开始0→旁白1→轮2→轮3→结束4
        assertThat(resp.getEdges().get(0).getSource()).isEqualTo(0);
        assertThat(resp.getEdges().get(0).getTarget()).isEqualTo(1);
        assertThat(resp.getEdges().get(1).getSource()).isEqualTo(1);
        assertThat(resp.getEdges().get(1).getTarget()).isEqualTo(2);
        assertThat(resp.getEdges().get(3).getTarget()).isEqualTo(4);
    }

    @Test
    void importScriptSkipsNarrationWhenBlank() {
        // v1.2：narration 空/null → 不插旁白节点（陪练者先说场景）
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("trainer");
        req.setNarration("   ");
        req.setRounds(List.of(round(1)));

        AiGenRespDtos.ImportScriptResp resp = service.importScript(req);

        assertThat(resp.getNodes()).hasSize(3);
        assertThat(resp.getNodes().get(1).getNodeType()).isEqualTo(2);
        assertThat(resp.getEdges()).hasSize(2);
        assertThat(resp.getEdges().get(0).getSource()).isEqualTo(0);
        assertThat(resp.getEdges().get(0).getTarget()).isEqualTo(1);
    }

    @Test
    void importScriptRejectsEmptyRounds() {
        AiGenDtos.ImportScriptReq req = new AiGenDtos.ImportScriptReq();
        req.setFirstSpeaker("trainer");
        req.setRounds(List.of());
        assertThatThrownBy(() -> service.importScript(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少勾选一轮");
    }

    @Test
    void materialsTextJoinsWithFileNameAndTruncationMark() {
        AiGenDtos.MaterialItem m1 = new AiGenDtos.MaterialItem();
        m1.setFileName("产品介绍.ppt");
        m1.setText("快反王2 跳针率下降97%");
        AiGenDtos.MaterialItem m2 = new AiGenDtos.MaterialItem();
        m2.setFileName("FAQ.doc");
        m2.setText("常见问题解答");
        m2.setTruncated(true);
        AiGenDtos.MaterialItem empty = new AiGenDtos.MaterialItem();
        empty.setFileName("空.txt");
        empty.setText("");

        String joined = service.joinMaterialsText(List.of(m1, m2, empty));

        assertThat(joined).contains("【素材：产品介绍.ppt】");
        assertThat(joined).contains("快反王2 跳针率下降97%");
        assertThat(joined).contains("【素材：FAQ.doc（已截断）】");
        assertThat(joined).doesNotContain("空.txt");
        assertThat(service.joinMaterialsText(null)).isEmpty();
    }

    private AiGenDtos.RoundItem round(int no) {
        AiGenDtos.RoundItem item = new AiGenDtos.RoundItem();
        item.setRound(no);
        item.setTrainerQuestion("Q" + no);
        item.setStandardScript("A" + no);
        item.setAnalysis("解析" + no);
        AiGenDtos.KeyPointItem kp = new AiGenDtos.KeyPointItem();
        kp.setContent("要点" + no);
        kp.setOrder(1);
        item.setKeyPoints(List.of(kp));
        return item;
    }
}
