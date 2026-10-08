package jack.digityhuman.onlineclassroom.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.UploadFileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 05 FP-05-1 回归：wav 临时上传必须走 multipart 分块（单次 PUT 大文件被 10054 掐断的 P0 修复）。
 * 断言：uploadFile 被调用且 partSize=8MB、enableCheckpoint=true；不再走 putObject。
 */
class OssTempServiceTest {

    @TempDir
    Path tempDir;

    private OSS oss = mock(OSS.class);

    private OssTempService service() {
        // 四项配置齐全（configured=true），注入 mock 客户端绕过懒初始化
        return new OssTempService("oss-cn-beijing.aliyuncs.com", "bucket", "ak", "sk", oss);
    }

    @Test
    void uploadUsesMultipartWithEightMbPartsAndCheckpoint() throws Throwable {
        Path wav = Files.write(tempDir.resolve("cw-1.wav"), new byte[]{1, 2, 3});
        when(oss.generatePresignedUrl(anyString(), anyString(), any()))
                .thenReturn(new URL("https://bucket.oss-cn-beijing.aliyuncs.com/kng-asr-tmp/cw-1.wav?sig=x"));

        String url = service().uploadAndPresign(wav, "cw-1");

        assertThat(url).contains("kng-asr-tmp/cw-1.wav");
        // 分块上传参数是本 FP 的核心契约：8MB/块 + 断点续传
        verify(oss).uploadFile(any(UploadFileRequest.class));
        verify(oss, never()).putObject(anyString(), anyString(), any(java.io.File.class));
        verify(oss).generatePresignedUrl(eq("bucket"), eq("kng-asr-tmp/cw-1.wav"), any());
    }

    @Test
    void uploadFileRequestCarriesExpectedMultipartSettings() throws Throwable {
        // 捕获请求对象逐字段断言（partSize/enableCheckpoint/taskNum/uploadFile）
        org.mockito.ArgumentCaptor<UploadFileRequest> captor =
                org.mockito.ArgumentCaptor.forClass(UploadFileRequest.class);
        Path wav = Files.write(tempDir.resolve("cw-2.wav"), new byte[]{1});
        when(oss.generatePresignedUrl(anyString(), anyString(), any()))
                .thenReturn(new URL("https://bucket.oss-cn-beijing.aliyuncs.com/x?sig=y"));

        service().uploadAndPresign(wav, "cw-2");
        verify(oss).uploadFile(captor.capture());

        UploadFileRequest request = captor.getValue();
        assertThat(request.getPartSize()).isEqualTo(8L * 1024 * 1024);
        assertThat(request.isEnableCheckpoint()).isTrue();
        assertThat(request.getTaskNum()).isEqualTo(3);
        assertThat(request.getUploadFile()).isEqualTo(wav.toFile().getAbsolutePath());
    }

    @Test
    void uploadFailureConvergesToBizException() throws Throwable {
        // uploadFile 声明 throws Throwable：受检异常也必须收敛为业务异常，不能漏到 worker 线程
        Path wav = Files.write(tempDir.resolve("cw-3.wav"), new byte[]{1});
        when(oss.uploadFile(any(UploadFileRequest.class)))
                .thenThrow(new Exception("Connection reset by peer: socket write error"));

        assertThatThrownBy(() -> service().uploadAndPresign(wav, "cw-3"))
                .isInstanceOf(jack.digityhuman.common.api.BizException.class)
                .hasMessageContaining("字幕音频上传 OSS 失败");
    }

    @Test
    void unconfiguredOssRejectsBeforeAnyClientCall() {
        OssTempService unconfigured =
                new OssTempService("", "", "", "", null);

        assertThatThrownBy(() -> unconfigured.uploadAndPresign(tempDir.resolve("x.wav"), "cw-4"))
                .isInstanceOf(jack.digityhuman.common.api.BizException.class)
                .hasMessageContaining("字幕功能未配置 OSS");
    }
}
