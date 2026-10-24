package com.mtxgdn.onebot;

import com.mtxgdn.db.QqBindingDAO;
import com.mtxgdn.db.QqOfficialBindingDAO;

/**
 * OneBot 渠道的 QQ 号绑定服务。
 *
 * <p>与 QQ 官方开放平台的绑定是<b>两套身份体系</b>：OneBot 拿的是真实 QQ 号，
 * 官方平台下发的是 openid，分别存在 {@code qq_bindings} 与
 * {@code qq_official_bindings} 两张表里。本类只负责前者，
 * SQL 已全部抽到 {@link QqBindingDAO}。</p>
 */
public class QqBindingService {

    /** {@code qq_bindings} 的读写入口（无状态，可共享）。 */
    private static final QqBindingDAO BINDING_DAO = new QqBindingDAO();

    public QqBinding findByQq(String qqNumber) {
        return BINDING_DAO.findByQq(qqNumber);
    }

    public QqBinding findByUserId(Long userId) {
        return BINDING_DAO.findByUserId(userId);
    }

    /**
     * 跨平台查询用户绑定（仅用于展示）：先在 OneBot 表查，再查 QQ官方表。
     * 一个用户可能在两端分别绑定（各自独立）。
     */
    public QqBinding findAnyBindingByUserId(Long userId) {
        QqBinding b = findByUserId(userId);
        if (b != null) return b;
        return new QqOfficialBindingDAO().findByUserIdAnyApp(userId);
    }

    public void bind(String qqNumber, Long userId) {
        QqBinding existingByQq = findByQq(qqNumber);
        if (existingByQq != null) {
            throw new RuntimeException("该QQ号已绑定用户: " + existingByQq.getUserId());
        }

        QqBinding existingByUser = findByUserId(userId);
        if (existingByUser != null) {
            throw new RuntimeException("该用户已绑定QQ号: " + existingByUser.getQqNumber());
        }

        BINDING_DAO.insertBinding(qqNumber, userId);
    }

    public void unbindByQq(String qqNumber) {
        int affected = BINDING_DAO.deleteByQq(qqNumber);
        if (affected == 0) {
            throw new RuntimeException("该QQ号未绑定任何用户");
        }
    }

    public void unbindByUserId(Long userId) {
        int affected = BINDING_DAO.deleteByUserId(userId);
        if (affected == 0) {
            throw new RuntimeException("该用户未绑定任何QQ");
        }
    }
}
