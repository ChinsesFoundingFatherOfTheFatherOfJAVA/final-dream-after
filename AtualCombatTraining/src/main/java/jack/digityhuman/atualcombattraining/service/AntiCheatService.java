package jack.digityhuman.atualcombattraining.service;

/**
 * 防作弊服务（契约 03 §12：切屏与人脸事件审计与结算）
 * <p>
 * 切屏规则（§12.1）：screen.hidden 事件永远落审计；forbid 模式第 1 次即结束 attempt
 * （reason=screen_violation），allow 模式超过 screenSwitchLimit 后结束。
 * 结算走 lifecycleService.finish（CAS 幂等），并发上报只结算一次。
 * <p>
 * 人脸（§12.2）：face.pass/face.fail/camera.lost 事件照实落审计；
 * 抓拍存储与 provider 比对属 FaceVerificationProvider 配置化适配器（后续接入），
 * provider 未配置时前端不启用抓帧。
 */
public interface AntiCheatService {

    /**
     * 上报事件：落审计 + 按规则结算切屏违规。
     *
     * @param attemptId     attempt ID
     * @param userId        学员（归属校验）
     * @param eventType     screen.hidden / face.pass / face.fail / camera.lost
     * @param clientEventId 客户端事件 ID（幂等键）
     * @param dataJson      附加数据（可空）
     * @return {recorded: 是否新记录, violation: 是否触发切屏违规结束}
     */
    AntiCheatResult reportEvent(String attemptId, String userId, String eventType,
                                String clientEventId, String dataJson);

    record AntiCheatResult(boolean recorded, boolean violation) {
    }
}
