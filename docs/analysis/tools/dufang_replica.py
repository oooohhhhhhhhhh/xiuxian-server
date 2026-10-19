# -*- coding: utf-8 -*-
"""
金银阁 (xiuxian_dufang) 结算逻辑 1:1 复刻与量化
目标文件：nonebot_plugin_xiuxian_2/xiuxian/xiuxian_dufang/__init__.py

复刻依据（原文件行号）：
  :93-99    余额校验  -> 只发消息，缺 await dufang.finish()（致命缺陷）
  :100-106  price==0  -> 只发消息，缺 finish()
  :108      value = random.randint(1, 6)
  :111-119  大赢  +price
  :121-129  小赢  +price
  :130-138  奇赢  +price
  :139-146  偶赢  +price（漏发骰子）
  :148-155  猜赢  +price*5（漏发骰子）
  :157-164  输    -price（漏发骰子）
"""
import random
import re
import statistics


def handler(stone, price, mode, guess=None, guarded=False):
    """返回 (新余额, 结果, 骰子值, 是否发出骰子)"""
    # ---- :93-99 余额校验 ----
    if stone < price:
        if guarded:
            return stone, 'insufficient', None, False
        # 原版：不 finish，继续往下掷骰结算
    # ---- :100-106 price==0 校验 ----
    elif price == 0:
        if guarded:
            return stone, 'zero_stake', None, False

    # ---- :108 ----
    value = random.randint(1, 6)

    # ---- :111-164 六分支结算 ----
    if value >= 4 and mode == "大":
        return stone + price, 'win', value, True
    elif value <= 3 and mode == "小":
        return stone + price, 'win', value, True
    elif value % 2 == 1 and mode == "奇":
        return stone + price, 'win', value, True
    elif value % 2 == 0 and mode == "偶":
        return stone + price, 'win', value, False   # :139-146 漏发骰子
    elif str(value) == str(guess) and mode == "猜":
        return stone + price * 5, 'win', value, False  # :148-155 漏发骰子
    else:
        return stone - price, 'lose', value, False   # :157-164 漏发骰子


def scenario(name, stone, price, mode, guess=None, trials=200000, guarded=False):
    deltas, finals, wins, neg, dice_missing = [], [], 0, 0, 0
    for _ in range(trials):
        new, outcome, _v, dice = handler(stone, price, mode, guess, guarded)
        deltas.append(new - stone)
        finals.append(new)
        if outcome == 'win':
            wins += 1
            if not dice:
                dice_missing += 1
        if new < 0:
            neg += 1
    mean = statistics.fmean(deltas)
    sd = statistics.pstdev(deltas)
    return {
        '场景': name, '次数': trials,
        '单次期望': round(mean, 2),
        '期望/下注额': round(mean / price, 4) if price else 0.0,
        '标准差': round(sd, 2),
        '胜率': round(wins / trials, 4),
        '负余额率': round(neg / trials, 4),
        '赢但无骰子率': round(dice_missing / trials, 4),
        '最小余额': min(finals), '最大余额': max(finals),
    }


def show(rows, title):
    print('=' * 100)
    print(title)
    print('=' * 100)
    hdr = ['场景', '次数', '单次期望', '期望/下注额', '标准差', '胜率', '负余额率', '赢但无骰子率', '最小余额', '最大余额']
    print(' | '.join(hdr))
    for r in rows:
        print(' | '.join(str(r[h]) for h in hdr))
    print()


random.seed(20240607)

# ---------- 一、正常路径：验证 EV=0 ----------
rows = []
for mode, guess in [('大', None), ('小', None), ('奇', None), ('偶', None), ('猜', 3)]:
    label = f'押{mode}' + (f'{guess}' if guess else '')
    rows.append(scenario(f'{label} 100（余额1000）', 1000, 100, mode, guess))
show(rows, '一、正常路径（余额充足）—— 验证"零庄家优势"')

# ---------- 二、致命缺陷：余额不足仍结算 ----------
rows = []
for mode, guess in [('大', None), ('小', None), ('奇', None), ('偶', None), ('猜', 3)]:
    label = f'押{mode}' + (f'{guess}' if guess else '')
    rows.append(scenario(f'{label} 100万（余额0）', 0, 1000000, mode, guess))
show(rows, '二、致命缺陷：余额不足仍掷骰结算（余额 0，下注 100 万）')

# ---------- 三、修复后对照 ----------
rows = []
for mode, guess in [('大', None), ('猜', 3)]:
    label = f'押{mode}' + (f'{guess}' if guess else '')
    rows.append(scenario(f'{label} 100万（余额0·已修）', 0, 1000000, mode, guess, guarded=True))
show(rows, '三、修复后对照（余额不足即返回）')

# ---------- 四、36 格行为矩阵 ----------
print('=' * 100)
print('四、36 格行为矩阵（静态枚举：模式 × 骰子值）')
print('=' * 100)
print('模式 | 骰子 | 余额变化 | 骰子动画 | 结果')
for mode in ['大', '小', '奇', '偶', '猜']:
    for v in range(1, 7):
        # 用确定性骰子值驱动
        if v >= 4 and mode == "大":
            d, dice, oc = '+1×注', '是', '赢'
        elif v <= 3 and mode == "小":
            d, dice, oc = '+1×注', '是', '赢'
        elif v % 2 == 1 and mode == "奇":
            d, dice, oc = '+1×注', '是', '赢'
        elif v % 2 == 0 and mode == "偶":
            d, dice, oc = '+1×注', '否(缺陷)', '赢'
        elif mode == "猜" and v == 3:
            d, dice, oc = '+5×注', '否(缺陷)', '赢'
        else:
            d, dice, oc = '-1×注', '否(缺陷)', '输'
        print(f'{mode} | {v} | {d} | {dice} | {oc}')
print()

# ---------- 五、骰子动画缺失率 ----------
print('=' * 100)
print('五、骰子动画缺失率（按模式）')
print('=' * 100)
for mode, guess in [('大', None), ('小', None), ('奇', None), ('偶', None), ('猜', 3)]:
    n, missing = 100000, 0
    for _ in range(n):
        _new, outcome, _v, dice = handler(1000, 100, mode, guess)
        if not dice:
            missing += 1
    print(f'  押{mode}{guess if guess else ""}: {missing/n:.2%} 的回合不显示骰子')
print()

# ---------- 六、正则边界测试 ----------
print('=' * 100)
print('六、输入解析边界（复刻 :29-34 的正则）')
print('=' * 100)
pat = re.compile(r"(金银阁)\s?(\d+)\s?([大|小|奇|偶|猜])?\s?(\d+)?", re.I)
cases = [
    '金银阁10大', '金银阁 10 大', '金银阁100', '金银阁10猜3', '金银阁 10 猜 3',
    '金银阁10猜', '金银阁10猜7', '金银阁0大', '金银阁10|', '随便说说金银阁10大',
    '金银阁99999999999999999999大', '金银阁10大5', '金银阁 10 小 ', '金银阁',
]
print('输入 | 匹配 | 捕获组(args) | 说明')
for c in cases:
    m = pat.search(c)
    if m:
        g = m.groups()
        note = ''
        if g[2] == '|':
            note = '字符类误匹配竖线 -> 走"输"分支白扣钱'
        elif m.start() != 0 or m.end() != len(c):
            note = '未锚定：夹在长文本中也会触发'
        elif g[1] and int(g[1]) > 9_223_372_036_854_775_807:
            note = '超出 int64 -> 赢时 SQLite OverflowError'
        print(f'{c!r} | 是 | {g} | {note}')
    else:
        print(f'{c!r} | 否 | - | -')
print()

# ---------- 七、赌徒破产 ----------
print('=' * 100)
print('七、赌徒破产推演（零期望 + 有限本金，本金1000，每局押100，最多200局）')
print('=' * 100)
ruin = 0
exps = 2000
for _ in range(exps):
    bal = 1000
    for _r in range(200):
        if bal < 100:
            break
        bal, _o, _v, _d = handler(bal, 100, '大')
    if bal < 100:
        ruin += 1
print(f'  200 局内破产率: {ruin/exps:.1%}   （零期望游戏 + 有限本金 => 长期破产概率趋近 100%）')
print()

# ---------- 八、负余额的连锁后果 ----------
print('=' * 100)
print('八、负余额的连锁后果（偷灵石 victim 为负数时）')
print('=' * 100)
for victim in (0, -100, -1000000):
    try:
        lo = int(0.01 * victim)
        hi = int(0.50 * victim)
        got = random.randint(lo, hi)
        print(f'  victim.stone={victim:>10}  -> randint({lo}, {hi}) = {got}')
    except Exception as e:
        print(f'  victim.stone={victim:>10}  -> randint({int(0.01*victim)}, {int(0.50*victim)}) 抛出 {type(e).__name__}: {e}')
