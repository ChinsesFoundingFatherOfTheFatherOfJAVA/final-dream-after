package jack.digityhuman.onlineclassroom.service;

import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05 FP-05-4 回归：热词表 ID 配置透传。
 * 录音文件识别 API 无 context 参数，paraformer-v2 热词唯一入口 = vocabulary_id（SDK TranscriptionParam 自带字段）。
 */
class BailianAsrEngineTest {

    private BailianAsrEngine engine(String vocabularyId) {
        // base-url 传空串 = 不覆盖 SDK 默认（避免单测污染全局静态 Constants）
        return new BailianAsrEngine("sk-test", "paraformer-v2", vocabularyId, "",
                new OssTempService("", "", "", ""), null);
    }

    @Test
    void baseUrlOverrideAppliedWhenConfigured() {
        // 平台切换：配置 base-url 时覆盖 SDK 全局 Constants（千问AI平台入口）
        com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = "https://default.example/api/v1";
        try {
            new BailianAsrEngine("sk-test", "paraformer-v2", "",
                    "https://maas.qianwenaiapi.com/api/v1",
                    new OssTempService("", "", "", ""), null);
            assertThat(com.alibaba.dashscope.utils.Constants.baseHttpApiUrl)
                    .isEqualTo("https://maas.qianwenaiapi.com/api/v1");
        } finally {
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = "https://default.example/api/v1";
        }
    }

    @Test
    void vocabularyIdPassedWhenConfigured() {
        BailianAsrEngine engine =
                engine("vocab-kng-2ca5d66301134ad9b280837fbe5f4b5f");

        TranscriptionParam param = engine.buildParam("https://oss/x.wav");

        assertThat(param.getVocabularyId())
                .isEqualTo("vocab-kng-2ca5d66301134ad9b280837fbe5f4b5f");
        assertThat(param.getModel()).isEqualTo("paraformer-v2");
        assertThat(param.getFileUrls()).containsExactly("https://oss/x.wav");
    }

    @Test
    void vocabularyIdAbsentWhenNotConfigured() {
        // 未配置（空串）时请求体不出现该参数：行为与现状完全一致
        TranscriptionParam param = engine("").buildParam("https://oss/x.wav");

        assertThat(param.getVocabularyId()).isNull();
    }
}
