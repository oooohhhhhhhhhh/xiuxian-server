package com.mtxgdn.onebot;

public class QqBinding {

    private Long id;
    private String qqNumber;
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
