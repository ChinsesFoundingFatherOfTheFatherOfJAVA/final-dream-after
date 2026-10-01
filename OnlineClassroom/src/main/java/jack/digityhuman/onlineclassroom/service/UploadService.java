package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.entity.KngUploadChunk;
import jack.digityhuman.onlineclassroom.mapper.KngUploadChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 分片上传：暂存、断点续传查询、按序合并和内存级秒传。
 */
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final int METADATA_INDEX = -1;
    private static final Map<String, String> COMPLETED_BY_MD5 = new ConcurrentHashMap<>();

    private final KngUploadChunkMapper chunkMapper;
    private final StorageService storageService;

    /** 初始化分片上传：秒传检查（同哈希直接返回已完成），否则登记元数据并返回 uploadId。 */
    @Transactional
    public InitResult init(InitRequest request) {
        if (request == null || !StringUtils.hasText(request.fileName())) {
            throw new BizException("文件名不能为空");
        }
        if (request.fileSize() == null || request.fileSize() <= 0) {
            throw new BizException("文件大小不正确");
        }
        if (request.totalChunks() == null || request.totalChunks() <= 0) {
            throw new BizException("分片数量不正确");
        }
        String md5 = StringUtils.hasText(request.md5()) ? request.md5().trim() : "";
        if (StringUtils.hasText(md5)) {
            String completed = COMPLETED_BY_MD5.get(md5);
            if (completed != null) {
                return new InitResult(null, List.of(), true, completed);
            }
        }
        String uploadId = StringUtils.hasText(request.uploadId())
                ? request.uploadId().trim()
                : java.util.UUID.randomUUID().toString();
        KngUploadChunk metadata = chunkMapper.selectOne(Wrappers.<KngUploadChunk>lambdaQuery()
                .eq(KngUploadChunk::getUploadId, uploadId)
                .eq(KngUploadChunk::getChunkIndex, METADATA_INDEX));
        if (metadata == null) {
            metadata = new KngUploadChunk();
            metadata.setUploadId(uploadId);
            metadata.setChunkIndex(METADATA_INDEX);
            metadata.setTotalChunks(request.totalChunks());
            metadata.setFileName(request.fileName());
            metadata.setFileSize(request.fileSize());
            metadata.setMd5(md5);
            metadata.setRelDir(uploadId);
            metadata.setCreatedAt(LocalDateTime.now());
            chunkMapper.insert(metadata);
        }
        List<Integer> uploaded = uploadedChunks(uploadId);
        return new InitResult(uploadId, uploaded, false, null);
    }

    /** 接收单个分片：按序写入临时目录，重复上传同序号分片幂等覆盖。 */
    @Transactional
    public void uploadChunk(String uploadId, int chunkIndex, MultipartFile file) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BizException("上传标识不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("上传分片不能为空");
        }
        KngUploadChunk metadata = metadata(uploadId);
        if (chunkIndex < 0 || chunkIndex >= metadata.getTotalChunks()) {
            throw new BizException("分片序号错误");
        }
        List<Integer> uploaded = uploadedChunks(uploadId);
        if (chunkIndex != 0 && !uploaded.contains(chunkIndex - 1)) {
            throw new BizException("分片序号错误");
        }
        KngUploadChunk existing = chunkMapper.selectOne(Wrappers.<KngUploadChunk>lambdaQuery()
                .eq(KngUploadChunk::getUploadId, uploadId)
                .eq(KngUploadChunk::getChunkIndex, chunkIndex));
        if (existing != null) {
            return;
        }
        try {
            var input = file.getInputStream();
            storageService.storeChunk(uploadId, chunkIndex, input);
            input.close();
        } catch (IOException exception) {
            throw new BizException("分片保存失败");
        }
        KngUploadChunk chunk = new KngUploadChunk();
        chunk.setUploadId(uploadId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTotalChunks(metadata.getTotalChunks());
        chunk.setFileName(metadata.getFileName());
        chunk.setFileSize(metadata.getFileSize());
        chunk.setMd5(metadata.getMd5());
        chunk.setRelDir(metadata.getRelDir());
        chunk.setCreatedAt(LocalDateTime.now());
        chunkMapper.insert(chunk);
    }

    @Transactional
    /** 合并分片：校验分片完整性后按序拼接为最终文件，清理临时分片并落盘元数据。 */
    public MergeResult merge(MergeRequest request) {
        if (request == null || !StringUtils.hasText(request.uploadId())) {
            throw new BizException("上传标识不能为空");
        }
        KngUploadChunk metadata = metadata(request.uploadId());
        List<Integer> uploaded = uploadedChunks(request.uploadId());
        if (uploaded.size() != metadata.getTotalChunks()) {
            throw new BizException("上传分片不完整");
        }
        for (int index = 0; index < metadata.getTotalChunks(); index++) {
            if (!uploaded.contains(index)) {
                throw new BizException("上传分片不完整");
            }
        }
        String extension = extension(StringUtils.hasText(request.fileName())
                ? request.fileName() : metadata.getFileName());
        Path relative = storageService.mergeChunks(
                request.uploadId(), extension, metadata.getTotalChunks());
        String storagePath = "/kng-files/" + relative.toString().replace('\\', '/');
        if (StringUtils.hasText(metadata.getMd5())) {
            COMPLETED_BY_MD5.put(metadata.getMd5(), storagePath);
        }
        chunkMapper.delete(Wrappers.<KngUploadChunk>lambdaQuery()
                .eq(KngUploadChunk::getUploadId, request.uploadId()));
        return new MergeResult(storagePath, metadata.getFileSize());
    }

    private KngUploadChunk metadata(String uploadId) {
        KngUploadChunk metadata = chunkMapper.selectOne(Wrappers.<KngUploadChunk>lambdaQuery()
                .eq(KngUploadChunk::getUploadId, uploadId)
                .eq(KngUploadChunk::getChunkIndex, METADATA_INDEX));
        if (metadata == null) {
            throw new BizException("上传任务不存在或已失效");
        }
        return metadata;
    }

    private List<Integer> uploadedChunks(String uploadId) {
        return chunkMapper.selectList(Wrappers.<KngUploadChunk>lambdaQuery()
                        .eq(KngUploadChunk::getUploadId, uploadId)
                        .ge(KngUploadChunk::getChunkIndex, 0)
                        .orderByAsc(KngUploadChunk::getChunkIndex))
                .stream()
                .map(KngUploadChunk::getChunkIndex)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private String extension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "");
        return extension.length() > 16 ? extension.substring(0, 16) : extension;
    }

    public record InitRequest(
            String fileName,
            Long fileSize,
            String md5,
            Integer chunkSize,
            Integer totalChunks,
            String uploadId) {
    }

    public record InitResult(
            String uploadId,
            List<Integer> uploadedChunks,
            boolean instant,
            String storagePath) {
    }

    public record MergeRequest(String uploadId, String fileName) {
    }

    public record MergeResult(String storagePath, Long fileSize) {
    }
}
