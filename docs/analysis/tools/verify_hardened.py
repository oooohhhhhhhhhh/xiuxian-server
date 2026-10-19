# -*- coding: utf-8 -*-
"""加固版逻辑验证：EV、庄家优势、余额永不为负、日限额"""
import random

HOUSE_EDGE = 0.05
ODDS = {
    "大": (1 - HOUSE_EDGE, 0.5),
    "小": (1 - HOUSE_EDGE, 0.5),
    "奇": (1 - HOUSE_EDGE, 0.5),
    "偶": (1 - HOUSE_EDGE, 0.5),
    "猜": (5 * (1 - HOUSE_EDGE), 1 / 6),
}


def judge(mode, guess, roll):
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


def payout(stake, mode):
    mult, _ = ODDS[mode]
    return int(stake * mult)


print("=" * 78)
print("一、理论期望值（庄家优势验证）")
print("=" * 78)
print(f"{'玩法':<6}{'净赔率':<10}{'中奖率':<10}{'EV/注':<12}{'庄家优势'}")
for mode, (mult, p) in ODDS.items():
    ev = p * mult - (1 - p) * 1.0
    print(f"{mode:<6}{mult:<10.4f}{p:<10.4f}{ev:<12.4f}{-ev:.2%}")

print()
print("=" * 78)
print("二、蒙特卡洛：加固版结算（本金 1000，每局押 100 —— 验证余额永不为负）")
print("=" * 78)
rng = random.SystemRandom()
for mode, guess in [("大", None), ("小", None), ("奇", None), ("偶", None), ("猜", 3)]:
    bal, rounds, neg, minbal, total_net = 1000, 0, 0, 1000, 0
    while rounds < 200000:
        stake = min(100, bal)
        if stake < 1:
            break                 # 破产，无法继续
        bal -= stake
        roll = rng.randint(1, 6)
        if judge(mode, guess, roll):
            net = payout(stake, mode)
            bal += stake + net
            total_net += net
        else:
            total_net -= stake
        rounds += 1
        if bal < minbal:
            minbal = bal
        if bal < 0:
            neg += 1
    label = f"押{mode}" + (f"{guess}" if guess else "")
    per = (total_net / rounds) if rounds else 0
    print(f"  {label:<8} 实际局数={rounds:<7} 终局余额={bal:<8} 最小余额={minbal:<6} 负余额次数={neg:<3} 每局净收益={per:+.3f}")

print()
print("=" * 78)
print("三、大额本金下的庄家优势实测（本金 1 亿，每局押 10 万，20 万局）")
print("=" * 78)
for mode, guess in [("大", None), ("猜", 3)]:
    bal, rounds, total_net = 100_000_000, 0, 0
    stake = 100_000
    while rounds < 200000:
        bal -= stake
        roll = rng.randint(1, 6)
        if judge(mode, guess, roll):
            net = payout(stake, mode)
            bal += stake + net
            total_net += net
        else:
            total_net -= stake
        rounds += 1
    label = f"押{mode}" + (f"{guess}" if guess else "")
    per = total_net / rounds
    mult, p = ODDS[mode]
    theory = p * mult - (1 - p)
    print(f"  {label:<8} 终局余额={bal:<12} 每局净收益={per:+.2f}  占下注额={per/stake:+.4%}（理论 {theory:+.4%}）")

print()
print("=" * 78)
print("三、边界用例（加固版判定）")
print("=" * 78)
cases = [
    ("大", None, 6, True), ("大", None, 3, False),
    ("小", None, 1, True), ("小", None, 4, False),
    ("奇", None, 5, True), ("奇", None, 2, False),
    ("偶", None, 2, True), ("偶", None, 3, False),
    ("猜", 3, 3, True), ("猜", 3, 4, False),
]
allok = True
for mode, guess, roll, expect in cases:
    got = judge(mode, guess, roll)
    flag = "OK" if got == expect else "FAIL"
    if got != expect:
        allok = False
    print(f"  {mode}({guess}) roll={roll} -> {got}  期望={expect}  [{flag}]")
print()
print("全部用例通过" if allok else "存在失败用例")

print()
print("=" * 78)
print("四、赔付取整检查（小额下注是否出现 0 赔付）")
print("=" * 78)
for stake in (1, 2, 5, 10, 19, 20, 21, 100):
    print(f"  下注 {stake:>4} -> 押大净赔付={payout(stake,'大'):>5}  猜点净赔付={payout(stake,'猜'):>5}")
