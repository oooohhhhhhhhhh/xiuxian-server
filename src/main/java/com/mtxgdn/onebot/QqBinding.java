package com.mtxgdn.onebot;

public class QqBinding {

    private Long id;
    private String qqNumber;
    /** QQ 官方通道：机器人 AppID（OneBot/QQ号 场景为空）；历史兼容行为空字符串 */
    private String appId;
    private Long userId;
    private String createdAt;

    public QqBinding() {
    }

    public QqBinding(String qqNumber, Long userId) {
        this.qqNumber = qqNumber;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQqNumber() {
        return qqNumber;
    }

    public void setQqNumber(String qqNumber) {
        this.qqNumber = qqNumber;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
