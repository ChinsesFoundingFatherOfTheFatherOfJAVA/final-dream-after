package jack.digityhuman.speechtraining.service;

import org.springframework.stereotype.Component;

/**
 * 用户上下文 - 钉钉同步未完成前的临时方案
 * TODO: 钉钉集成后替换为从钉钉获取真实用户信息
 */
@Component
public class UserContext {

    /**
     * 获取当前用户ID
     * 临时方案：返回固定占位值，钉钉同步完成后替换为真实实现
     */
    public String getCurrentUserId() {
        return "system_admin";
    }

    /**
     * 获取当前用户名
     * 临时方案：返回固定占位值，钉钉同步完成后替换为真实实现
     */
    public String getCurrentUserName() {
        return "系统管理员";
    }

    /**
     * 获取当前学员端学员ID
     * 临时方案：mock 固定学员（决策记录 FP-18），钉钉人员同步后替换为真实实现，接口签名不变
     */
    public String getStudentId() {
        return "mock_student_001";
    }

    /**
     * 获取当前学员端学员姓名
     * 临时方案：mock 固定姓名，钉钉人员同步后替换
     */
    public String getStudentName() {
        return "学员";
    }
}
