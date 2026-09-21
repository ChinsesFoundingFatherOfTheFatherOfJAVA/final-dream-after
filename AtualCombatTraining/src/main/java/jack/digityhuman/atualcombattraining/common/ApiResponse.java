package jack.digityhuman.atualcombattraining.common;

import lombok.Data;

/**
 * 统一响应结构（对齐 SpeechTraining 风格）
 * code=0 成功；code=1 业务失败（message 为用户可读中文）
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    /** 构造成功响应（无数据体） */
    public static <T> ApiResponse<T> success() {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(0);
        resp.setMessage("success");
        return resp;
    }

    /** 构造成功响应（携带数据体） */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(0);
        resp.setMessage("success");
        resp.setData(data);
        return resp;
    }

    /** 构造业务失败响应（code=1） */
    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(1);
        resp.setMessage(message);
        return resp;
    }
}
