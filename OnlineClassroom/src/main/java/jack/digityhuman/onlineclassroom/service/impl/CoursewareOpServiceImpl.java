package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.onlineclassroom.common.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareAttachment;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngRef;
import jack.digityhuman.onlineclassroom.entity.SysUserRead;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareAttachmentMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngRefMapper;
import jack.digityhuman.onlineclassroom.mapper.SysUserReadMapper;
import jack.digityhuman.onlineclassroom.service.CoursewareOpService;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 课件移动、复制、上下架、删除等操作事务。
 */
@Service
@RequiredArgsConstructor
public class CoursewareOpServiceImpl implements CoursewareOpService {

    private final CoursewareService coursewareService;
    private final KngCoursewareMapper coursewareMapper;
    private final KngCoursewareFileMapper fileMapper;
    private final KngCoursewareAttachmentMapper attachmentMapper;
    private final KngCatalogMapper catalogMapper;
    private final KngRefMapper refMapper;
    private final SysUserReadMapper userReadMapper;

    @Transactional
    public KngCoursewareDtos.CountResponse takeDown(String id) {
        KngCourseware courseware = coursewareService.require(id);
        if (!Objects.equals(courseware.getStatus(), 2)) {
            throw new BizException("仅已发布课件可下架");
        }
        courseware.setStatus(3);
        courseware.setOnSale(0);
        courseware.setUpdatedAt(LocalDateTime.now());
        coursewareMapper.updateById(courseware);
        return new KngCoursewareDtos.CountResponse(1);
    }

    @Transactional
    public KngCoursewareDtos.CountResponse batchTakeDown(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = ids(request == null ? null : request.ids());
        for (String id : ids) {
            KngCourseware courseware = coursewareService.require(id);
            if (!Objects.equals(courseware.getStatus(), 2)) {
                throw new BizException("存在非已发布课件，无法批量下架");
            }
        }
        for (String id : ids) {
            KngCourseware courseware = coursewareService.require(id);
            courseware.setStatus(3);
            courseware.setOnSale(0);
            courseware.setUpdatedAt(LocalDateTime.now());
            coursewareMapper.updateById(courseware);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    @Transactional
    public KngCoursewareDtos.CountResponse batchPutOn(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = ids(request == null ? null : request.ids());
        for (String id : ids) {
            KngCourseware courseware = coursewareService.require(id);
            if (!Objects.equals(courseware.getStatus(), 3)) {
                throw new BizException("存在非已下架课件，无法批量上架");
            }
        }
        for (String id : ids) {
            KngCourseware courseware = coursewareService.require(id);
            courseware.setStatus(2);
            courseware.setOnSale(1);
            courseware.setUpdatedAt(LocalDateTime.now());
            coursewareMapper.updateById(courseware);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    @Transactional
    public KngCoursewareDtos.CountResponse move(KngCoursewareDtos.MoveRequest request) {
        List<String> ids = ids(request == null ? null : request.ids());
        String targetCatalogId = request == null ? null : request.targetCatalogId();
        KngCatalog target = StringUtils.hasText(targetCatalogId) ? catalogMapper.selectById(targetCatalogId) : null;
        if (target == null) {
            throw new BizException("请选择目标分类");
        }
        for (String id : ids) {
            KngCourseware courseware = coursewareService.require(id);
            courseware.setCatalogId(target.getId());
            courseware.setCatalogName(target.getName());
            courseware.setUpdatedAt(LocalDateTime.now());
            coursewareMapper.updateById(courseware);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    @Transactional
    public KngCoursewareDtos.IdResponse copy(
            String id, KngCoursewareDtos.CopyRequest request, String userId) {
        KngCourseware source = coursewareService.require(id);
        String targetCatalogId = request == null || !StringUtils.hasText(request.targetCatalogId())
                ? source.getCatalogId() : request.targetCatalogId();
        KngCatalog target = catalogMapper.selectById(targetCatalogId);
        if (target == null) {
            throw new BizException("目标分类不存在");
        }

        KngCourseware copy = new KngCourseware();
        copy.setId(IdWorker.getIdStr());
        copy.setTitle(copyTitle(source.getTitle()));
        copy.setType(source.getType());
        copy.setCatalogId(target.getId());
        copy.setCatalogName(target.getName());
        copy.setStatus(0);
        copy.setOnSale(0);
        copy.setStandardMinutes(source.getStandardMinutes());
        copy.setStandardSeconds(source.getStandardSeconds());
        copy.setStandardDurationFollow(source.getStandardDurationFollow());
        copy.setStudyScore(source.getStudyScore());
        copy.setFinishStandardPercent(source.getFinishStandardPercent());
        copy.setDescription(source.getDescription());
        copy.setCoverUrl(source.getCoverUrl());
        copy.setCode(source.getCode());
        copy.setVersionStr(source.getVersionStr());
        copy.setPlatformNo(null);
        copy.setViewPermission(source.getViewPermission());
        copy.setDownloadEnabled(source.getDownloadEnabled());
        copy.setDownloadPermission(source.getDownloadPermission());
        copy.setAppSaveEnabled(source.getAppSaveEnabled());
        copy.setAppSavePermission(source.getAppSavePermission());
        copy.setSpeedEnabled(source.getSpeedEnabled());
        copy.setSpeedHoursCalc(source.getSpeedHoursCalc());
        copy.setAiSubtitleEnabled(source.getAiSubtitleEnabled());
        copy.setCopyOf(source.getId());
        copy.setContributorId(StringUtils.hasText(userId) ? userId : source.getContributorId());
        copy.setLanguage(source.getLanguage());
        copy.setCreatedBy(userId);
        copy.setCreatedByName(userName(userId));
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedAt(LocalDateTime.now());
        coursewareMapper.insert(copy);

        for (KngCoursewareFile sourceFile : coursewareService.files(source.getId())) {
            KngCoursewareFile file = new KngCoursewareFile();
            file.setId(IdWorker.getIdStr());
            file.setCoursewareId(copy.getId());
            file.setFileName(sourceFile.getFileName());
            file.setStoragePath(sourceFile.getStoragePath());
            file.setFileSize(sourceFile.getFileSize());
            file.setLanguage(sourceFile.getLanguage());
            file.setSortOrder(sourceFile.getSortOrder());
            file.setCreatedAt(LocalDateTime.now());
            fileMapper.insert(file);
        }
        for (KngCoursewareAttachment sourceAttachment : coursewareService.attachments(source.getId())) {
            KngCoursewareAttachment attachment = new KngCoursewareAttachment();
            attachment.setId(IdWorker.getIdStr());
            attachment.setCoursewareId(copy.getId());
            attachment.setFileName(sourceAttachment.getFileName());
            attachment.setStoragePath(sourceAttachment.getStoragePath());
            attachment.setFileSize(sourceAttachment.getFileSize());
            attachment.setCreatedAt(LocalDateTime.now());
            attachmentMapper.insert(attachment);
        }
        return new KngCoursewareDtos.IdResponse(copy.getId());
    }

    @Transactional
    public KngCoursewareDtos.CountResponse delete(String id) {
        coursewareService.require(id);
        assertNotReferenced(id);
        fileMapper.delete(Wrappers.<KngCoursewareFile>lambdaQuery()
                .eq(KngCoursewareFile::getCoursewareId, id));
        attachmentMapper.delete(Wrappers.<KngCoursewareAttachment>lambdaQuery()
                .eq(KngCoursewareAttachment::getCoursewareId, id));
        coursewareMapper.deleteById(id);
        return new KngCoursewareDtos.CountResponse(1);
    }

    @Transactional
    public KngCoursewareDtos.CountResponse batchDelete(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = ids(request == null ? null : request.ids());
        int deleted = 0;
        for (String id : ids) {
            delete(id);
            deleted++;
        }
        return new KngCoursewareDtos.CountResponse(deleted);
    }

    public KngCoursewareFile downloadFile(String id) {
        KngCourseware courseware = coursewareService.require(id);
        if (Objects.equals(courseware.getType(), 8)) {
            throw new BizException("外链课件无源文件");
        }
        List<KngCoursewareFile> files = coursewareService.files(id);
        if (files.isEmpty() || !StringUtils.hasText(files.get(0).getStoragePath())) {
            throw new BizException("课件源文件不存在");
        }
        return files.get(0);
    }

    public List<KngRef> references(String id) {
        coursewareService.require(id);
        return refMapper.selectList(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getCoursewareId, id)
                .orderByDesc(KngRef::getCreatedAt));
    }

    public KngCoursewareDtos.DownloadRecordResponse downloadRecords(String id) {
        coursewareService.require(id);
        return new KngCoursewareDtos.DownloadRecordResponse(0, List.of());
    }

    private void assertNotReferenced(String id) {
        Long count = refMapper.selectCount(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getCoursewareId, id));
        if (count != null && count > 0) {
            throw new BizException("课件已被课程引用，无法删除");
        }
    }

    private List<String> ids(List<String> raw) {
        if (raw == null) {
            throw new BizException("请选择课件");
        }
        List<String> ids = raw.stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException("请选择课件");
        }
        return ids;
    }

    private String copyTitle(String title) {
        String base = (StringUtils.hasText(title) ? title : "课件") + "-副本";
        String candidate = base;
        int index = 2;
        while (coursewareMapper.selectCount(Wrappers.<KngCourseware>lambdaQuery()
                .eq(KngCourseware::getTitle, candidate)) > 0) {
            candidate = base + "(" + index++ + ")";
        }
        return candidate;
    }

    private String userName(String userId) {
        if (!StringUtils.hasText(userId)) return "系统";
        List<SysUserRead> users = userReadMapper.selectByIds(List.of(userId));
        return users.isEmpty() ? "系统" : users.get(0).getName();
    }
}
