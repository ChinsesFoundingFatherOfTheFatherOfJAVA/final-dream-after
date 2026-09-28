package jack.digityhuman.speechtraining.dto.resp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRankRespTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rankItemSerializesIsMeContract() throws Exception {
        ProjectRankResp.RankItem item = new ProjectRankResp.RankItem();
        item.setRankIndex(1);
        item.setUserId("40022966");
        item.setFullname("周承浩");
        item.setScore(new BigDecimal("91.0"));
        item.setMe(true);

        String json = objectMapper.writeValueAsString(item);

        assertThat(json).contains("\"isMe\":true");
        assertThat(json).doesNotContain("\"me\":");
    }
}
