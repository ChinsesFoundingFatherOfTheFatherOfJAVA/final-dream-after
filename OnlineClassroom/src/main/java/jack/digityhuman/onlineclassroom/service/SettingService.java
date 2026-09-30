package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;

public interface SettingService {
    KngSettingDtos.Response getAll();

    void saveScore(KngSettingDtos.ScoreConf request, String userId);

    void saveCheat(KngSettingDtos.CheatSaveRequest request, String userId);

    void saveSpecial(KngSettingDtos.SpecialRequest request, String userId);

    void savePlay(KngSettingDtos.PlayConf request, String userId);

    void saveFace(KngSettingDtos.FaceConf request, String userId);
}
