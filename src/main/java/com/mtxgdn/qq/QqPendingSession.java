package com.mtxgdn.qq;

/**
 * 注册 / 绑定 / 改密 / 注销对话流程的待处理会话。
 *
 * <p>拆分前是 {@code QqOfficialAdapter} 的私有静态嵌套类，字段与两个构造器逐字搬过来
 * （{@code type} / {@code state} 的默认值、{@code sourceGroupId} 的语义都不变）。
 * 现在由 {@link QqAdapterContext#pendingSessions} 持有、由 {@link QqAccountFlow}
 * 推进状态机、由 {@link QqMessageRouter} 判断"这条消息是不是在走对话流程"。</p>
 *
 * <p>它是包级可见的普通类，不是公开 API：外部调用方拿不到、也不需要它。</p>
 */
final class QqPendingSession {

    Long sourceGroupId;
    String type;
    String state;
    String username;
    String password;
    String email;

    QqPendingSession(String type, Long sourceGroupId) {
        this.type = type;
        this.sourceGroupId = sourceGroupId;
        this.state = "WAITING_USERNAME";
    }

    QqPendingSession() {
        this.type = "bind";
        this.state = "WAITING_USERNAME";
    }
}
