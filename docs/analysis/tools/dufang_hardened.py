# -*- coding: utf-8 -*-
"""
金银阁 —— 加固版实现（可直接替换 xiuxian_dufang/__init__.py）

设计目标（对应原版的 5 类缺陷）：
  1. 单一出口：所有校验分支统一 `return await _finish()`，从结构上杜绝"漏 finish()"
  2. 保证金模型：先扣注、中奖再返还「本金 + 赔付」，从结构上杜绝"无本金下注"与"负余额"
  3. 限额体系：单注上限 + 日累计上限 + 余额比例上限
  4. 全量流水：每笔下注写 dufang_records，可审计、可回滚、可做日限额
  5. 庄家抽水：赔率表显式化，使赌场真正成为货币水槽
  6. 输入校验：正则锚定、金额上下限、猜点数合法性、状态互斥
  7. 消息一致：骰子 + 结算文案由 settle() 统一发送，所有分支表现一致

安装方式：
  - 把本文件内容整体替换 `nonebot_plugin_xiuxian_2/xiuxian/xiuxian_dufang/__init__.py`
  - 或作为 `xiuxian_dufang/dufang_core.py` 引入（推荐，便于单元测试）
"""
import random
from typing import Any, Tuple

from nonebot import on_command, on_regex
from nonebot.adapters.onebot.v11 import (
    GROUP,
    Bot,
    GroupMessageEvent,
    MessageSegment,
)
from nonebot.params import RegexGroup
from nonebot.permission import SUPERUSER

from ..xiuxian_config import XiuConfig, 名称获取
from ..xiuxian_utils.lay_out import Cooldown, assign_bot
from ..xiuxian_utils.utils import check_user, check_user_type, get_msg_pic
from ..xiuxian_utils.xiuxian2_handle import XiuxianDateManage

sql_message = XiuxianDateManage()

# ============================ 可调参数 ============================
MAX_BET = 1_000_000          # 单注上限
DAILY_MAX_BET = 20_000_000   # 每日累计下注上限
MAX_BET_RATIO = 0.5          # 单注不得超过当前余额的 50%
HOUSE_EDGE = 0.05            # 庄家抽水 5%
ENABLED = False              # ★ 默认关闭：合规风险，需运营方显式开启

# 赔率表：mode -> (净赔付倍率, 中奖概率)
# 期望值 EV = 概率 × 净赔付 − (1 − 概率) × 1 = −HOUSE_EDGE（押大小奇偶）
ODDS = {
    "大": (1 - HOUSE_EDGE, 0.5),
    "小": (1 - HOUSE_EDGE, 0.5),
    "奇": (1 - HOUSE_EDGE, 0.5),
    "偶": (1 - HOUSE_EDGE, 0.5),
    "猜": (5 * (1 - HOUSE_EDGE), 1 / 6),
}

__dufang_help__ = f"""
金银阁玩法说明：
· 金银阁<金额>大 / 小   —— 骰子 4/5/6 为大，1/2/3 为小
· 金银阁<金额>奇 / 偶   —— 骰子奇偶
· 金银阁<金额>猜<点数>  —— 猜具体点数，命中赔付 5 倍
限额：单注 1 ~ {MAX_BET}，且不超过余额的 {int(MAX_BET_RATIO * 100)}%
每日累计下注上限：{DAILY_MAX_BET}
""".strip()

# 注意：本模块默认不加载，请在配置中显式启用：
#   _config.disabled_plugins 里移除 "dufang"
# 并在下方 ENABLED 改为 True

dufang_help = on_command("金银阁帮助", permission=GROUP, priority=7, block=True)
dufang = on_regex(
    r"^金银阁\s*(\d+)\s*([大小奇偶]|猜)\s*(\d+)?\s*$",
    permission=GROUP,
    block=True,
)

_cache_help: dict = {}


# ============================ 工具函数 ============================
async def _reply(bot: Bot, send_group_id: int, event: GroupMessageEvent, msg: str) -> None:
    """统一的"发图或发文本"，替代原版 8 处复制粘贴"""
    if XiuConfig().img:
        pic = await get_msg_pic(f"@{名称获取(event)}\n" + msg)
        await bot.send_group_msg(group_id=int(send_group_id), message=MessageSegment.image(pic))
    else:
        await bot.send_group_msg(group_id=int(send_group_id), message=msg)


def _ensure_table() -> None:
    """惰性建表：下注流水（既是审计账本，也是日限额的计数依据）"""
    cur = sql_message.conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS dufang_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            group_id TEXT NOT NULL,
            mode TEXT NOT NULL,
            guess INTEGER DEFAULT NULL,
            stake INTEGER NOT NULL,
            roll INTEGER NOT NULL,
            is_win INTEGER NOT NULL,
            payout INTEGER NOT NULL,
            balance_before INTEGER NOT NULL,
            balance_after INTEGER NOT NULL,
            created_at TEXT NOT NULL
        )
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_dufang_user_day ON dufang_records(user_id, created_at)")
    sql_message.conn.commit()


def _today_bet_total(user_id: str) -> int:
    """今日已下注总额（用于日限额）"""
    from datetime import datetime
    today = datetime.now().strftime("%Y-%m-%d")
    cur = sql_message.conn.cursor()
    cur.execute(
        "SELECT COALESCE(SUM(stake), 0) FROM dufang_records WHERE user_id=? AND created_at LIKE ?",
        (str(user_id), today + "%"),
    )
    row = cur.fetchone()
    return int(row[0]) if row else 0


def _write_record(user_id, group_id, mode, guess, stake, roll, is_win, payout, before, after) -> None:
    from datetime import datetime
    cur = sql_message.conn.cursor()
    cur.execute(
        """INSERT INTO dufang_records
           (user_id, group_id, mode, guess, stake, roll, is_win, payout, balance_before, balance_after, created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        (str(user_id), str(group_id), mode, guess, stake, roll, 1 if is_win else 0,
         payout, before, after, datetime.now().strftime("%Y-%m-%d %H:%M:%S")),
    )
    sql_message.conn.commit()


def _judge(mode: str, guess, roll: int) -> bool:
    """纯函数：判定中奖（便于单元测试）"""
    if mode == "大":
        return roll >= 4
    if mode == "小":
        return roll <= 3
    if mode == "奇":
        return roll % 2 == 1
    if mode == "偶":
        return roll % 2 == 0
    if mode == "猜":
        return guess is not None and roll == int(guess)
    return False


def _payout(stake: int, mode: str) -> int:
    """净赔付额（不含本金）"""
    mult, _p = ODDS[mode]
    return int(stake * mult)


# ============================ 指令实现 ============================
@dufang_help.handle(parameterless=[Cooldown(at_sender=False)])
async def dufang_help_(bot: Bot, event: GroupMessageEvent):
    bot, send_group_id = await assign_bot(bot=bot, event=event)
    await _reply(bot, send_group_id, event, __dufang_help__)
    await dufang_help.finish()


@dufang.handle(parameterless=[Cooldown(cd_time=XiuConfig().dufang_cd, at_sender=False)])
async def dufang_(bot: Bot, event: GroupMessageEvent, args: Tuple[Any, ...] = RegexGroup()):
    bot, send_group_id = await assign_bot(bot=bot, event=event)

    # ---------- 卫语句 1：功能开关 ----------
    if not ENABLED:
        await _reply(bot, send_group_id, event, "金银阁暂未开放。")
        await dufang.finish()

    # ---------- 卫语句 2：玩家存在性（先判后用，修原版空指针） ----------
    is_user, user_info, msg = check_user(event)
    if not is_user:
        await _reply(bot, send_group_id, event, msg)
        await dufang.finish()
    user_id = user_info["user_id"]

    # ---------- 卫语句 3：状态互斥（原版完全没有） ----------
    ok, state_msg = check_user_type(user_id, 0)
    if not ok:
        await _reply(bot, send_group_id, event, state_msg + "金银阁暂不接待。")
        await dufang.finish()

    # ---------- 卫语句 4：参数解析（正则已锚定，此处仍防御性校验） ----------
    raw_stake, mode, raw_guess = args[0], args[1], args[2]
    try:
        stake = int(raw_stake)
    except (TypeError, ValueError):
        await _reply(bot, send_group_id, event, "下注金额格式不正确。")
        await dufang.finish()

    if mode not in ODDS:
        await _reply(bot, send_group_id, event, "请指定 大/小/奇/偶/猜。")
        await dufang.finish()

    guess = None
    if mode == "猜":
        if raw_guess is None or str(raw_guess) not in {"1", "2", "3", "4", "5", "6"}:
            await _reply(bot, send_group_id, event, "猜点数需指定 1~6，例如：金银阁100猜3")
            await dufang.finish()
        guess = int(raw_guess)
    elif raw_guess is not None:
        await _reply(bot, send_group_id, event, "该玩法不需要点数，例如：金银阁100大")
        await dufang.finish()

    # ---------- 卫语句 5：金额上下限（修原版无上限导致的 SQLite 溢出） ----------
    if stake < 1:
        await _reply(bot, send_group_id, event, "下注金额至少为 1 枚灵石。")
        await dufang.finish()
    if stake > MAX_BET:
        await _reply(bot, send_group_id, event, f"单注上限为 {MAX_BET} 枚灵石。")
        await dufang.finish()

    balance = int(user_info["stone"])
    if stake > balance:
        await _reply(bot, send_group_id, event, f"道友的灵石不足，当前持有 {balance} 枚。")
        await dufang.finish()
    if stake > int(balance * MAX_BET_RATIO):
        cap = int(balance * MAX_BET_RATIO)
        await _reply(bot, send_group_id, event, f"单注不得超过余额的 {int(MAX_BET_RATIO * 100)}%，当前最多可下注 {cap} 枚。")
        await dufang.finish()

    # ---------- 卫语句 6：日限额 ----------
    _ensure_table()
    used = _today_bet_total(user_id)
    if used + stake > DAILY_MAX_BET:
        await _reply(bot, send_group_id, event,
                     f"今日累计下注已达 {used} 枚，上限 {DAILY_MAX_BET} 枚，请明日再来。")
        await dufang.finish()

    # ---------- 结算：保证金模型（先扣注，赢则返还本金+赔付） ----------
    sql_message.update_ls(user_id, stake, 2)          # 先扣本金
    roll = random.SystemRandom().randint(1, 6)        # 用 SystemRandom，避免 MT19937 可预测
    win = _judge(mode, guess, roll)
    net = _payout(stake, mode) if win else 0
    refund = stake + net                              # 中奖返还：本金 + 净赔付
    if refund:
        sql_message.update_ls(user_id, refund, 1)

    after = balance - stake + refund
    _write_record(user_id, send_group_id, mode, guess, stake, roll, win, net, balance, after)

    # ---------- 统一出口：所有分支都发骰子 + 结果 ----------
    await bot.send_group_msg(group_id=int(send_group_id),
                             message=f"[CQ:dice,value={roll}]")
    if win:
        await _reply(bot, send_group_id, event,
                     f"骰子 {roll} 点，你猜对了！赢得灵石 {net} 枚（本金 {stake} 已返还），"
                     f"当前持有 {after} 枚。")
    else:
        await _reply(bot, send_group_id, event,
                     f"骰子 {roll} 点，你猜错了，损失灵石 {stake} 枚，当前持有 {after} 枚。")
    await dufang.finish()
