# 金银阁 (xiuxian_dufang) 行为仿真
# 目的：1:1 复刻 nonebot_plugin_xiuxian_2/xiuxian/xiuxian_dufang/__init__.py 的结算分支，
#       用蒙特卡洛量化「正常路径期望值」与「余额不足路径的白拿收益」。
# 复刻依据（行号）：
#   :93-99   余额校验（缺 finish）
#   :100-106 price==0 校验（缺 finish）
#   :108     value = random.randint(1, 6)
#   :111-119 大赢 +price
#   :121-129 小赢 +price
#   :130-138 奇赢 +price
#   :139-146 偶赢 +price
#   :148-155 猜赢 +price*5
#   :157-164 输 -price

$ErrorActionPreference = 'Stop'

function Invoke-Dufang {
    param(
        [long]$stone,      # 当前灵石
        [long]$price,      # 下注额
        [string]$mode,     # 大/小/奇/偶/猜
        [int]$guess = 0,   # 猜的点数
        [switch]$HonorBalanceCheck  # 若为真，模拟"修复后"行为（余额不足即返回）
    )
    # ---- :93-106 校验段（原版：只发消息，不 finish）----
    if ($stone -lt $price) {
        if ($HonorBalanceCheck) {
            return [pscustomobject]@{ delta = 0; stone = $stone; outcome = 'insufficient-guarded'; diceSent = $false }
        }
        # 原版继续往下走
    }
    elseif ($price -eq 0) {
        if ($HonorBalanceCheck) {
            return [pscustomobject]@{ delta = 0; stone = $stone; outcome = 'zero-stake-guarded'; diceSent = $false }
        }
    }

    # ---- :108 掷骰 ----
    $value = Get-Random -Minimum 1 -Maximum 7

    # ---- :111-164 结算 ----
    $delta = 0; $outcome = ''; $diceSent = $false
    if ($value -ge 4 -and $mode -eq '大') {
        $delta = $price; $outcome = 'win'; $diceSent = $true
    }
    elseif ($value -le 3 -and $mode -eq '小') {
        $delta = $price; $outcome = 'win'; $diceSent = $true
    }
    elseif (($value % 2 -eq 1) -and $mode -eq '奇') {
        $delta = $price; $outcome = 'win'; $diceSent = $true
    }
    elseif (($value % 2 -eq 0) -and $mode -eq '偶') {
        $delta = $price; $outcome = 'win'; $diceSent = $false   # :139-146 漏发骰子
    }
    elseif ($value -eq $guess -and $mode -eq '猜') {
        $delta = $price * 5; $outcome = 'win'; $diceSent = $false # :148-155 漏发骰子
    }
    else {
        $delta = -$price; $outcome = 'lose'; $diceSent = $false   # :157-164 漏发骰子
    }
    return [pscustomobject]@{ delta = $delta; stone = $stone + $delta; outcome = $outcome; diceSent = $diceSent; roll = $value }
}

function Measure-Scenario {
    param([string]$Name, [long]$stone, [long]$price, [string]$mode, [int]$guess = 0, [int]$Trials = 100000, [switch]$Guard)
    $sum = [double]0; $wins = 0; $losses = 0; $neg = 0; $minBal = [long]::MaxValue; $maxBal = [long]::MinValue
    for ($i = 0; $i -lt $Trials; $i++) {
        $r = Invoke-Dufang -stone $stone -price $price -mode $mode -guess $guess -HonorBalanceCheck:$Guard
        $sum += $r.delta
        if ($r.outcome -eq 'win') { $wins++ }
        elseif ($r.outcome -eq 'lose') { $losses++ }
        if ($r.stone -lt $minBal) { $minBal = $r.stone }
        if ($r.stone -gt $maxBal) { $maxBal = $r.stone }
        if ($r.stone -lt 0) { $neg++ }
    }
    [pscustomobject]@{
        场景        = $Name
        次数        = $Trials
        期望收益    = [math]::Round($sum / $Trials, 2)
        单次期望占比 = if ($price -gt 0) { [math]::Round(($sum / $Trials) / $price, 4) } else { 0 }
        胜率        = [math]::Round($wins / $Trials, 4)
        负余额率    = [math]::Round($neg / $Trials, 4)
        最小余额    = $minBal
        最大余额    = $maxBal
    }
}

Write-Output "==================== 一、正常路径（余额充足）期望值验证 ===================="
Write-Output "预期：零期望（EV=0），余额永不为负"
$res1 = @(
    Measure-Scenario -Name '押大 100（余额1000）'   -stone 1000 -price 100 -mode '大'
    Measure-Scenario -Name '押小 100（余额1000）'   -stone 1000 -price 100 -mode '小'
    Measure-Scenario -Name '押奇 100（余额1000）'   -stone 1000 -price 100 -mode '奇'
    Measure-Scenario -Name '押偶 100（余额1000）'   -stone 1000 -price 100 -mode '偶'
    Measure-Scenario -Name '猜3  100（余额1000）'   -stone 1000 -price 100 -mode '猜' -guess 3
)
$res1 | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

Write-Output "==================== 二、致命漏洞：余额不足仍结算 ===================="
Write-Output "预期：押大小奇偶 EV = +0.5×下注额（50% 白拿）；猜点数 EV = 0"
$res2 = @(
    Measure-Scenario -Name '余额0 押大 100万'        -stone 0 -price 1000000 -mode '大'
    Measure-Scenario -Name '余额0 押小 100万'        -stone 0 -price 1000000 -mode '小'
    Measure-Scenario -Name '余额0 押奇 100万'        -stone 0 -price 1000000 -mode '奇'
    Measure-Scenario -Name '余额0 押偶 100万'        -stone 0 -price 1000000 -mode '偶'
    Measure-Scenario -Name '余额0 猜3 100万'         -stone 0 -price 1000000 -mode '猜' -guess 3
    Measure-Scenario -Name '余额50 押大 100万'       -stone 50 -price 1000000 -mode '大'
)
$res2 | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

Write-Output "==================== 三、修复后对照（余额不足即返回）===================="
$res3 = @(
    Measure-Scenario -Name '余额0 押大 100万（已修）' -stone 0 -price 1000000 -mode '大' -Guard
    Measure-Scenario -Name '余额0 猜3 100万（已修）'  -stone 0 -price 1000000 -mode '猜' -guess 3 -Guard
)
$res3 | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

Write-Output "==================== 四、36 格行为矩阵（静态枚举）===================="
Write-Output "列：模式 / 骰子值 / 余额变化 / 是否发骰子 / 结果"
$matrix = @()
foreach ($m in @('大','小','奇','偶','猜')) {
    for ($v = 1; $v -le 6; $v++) {
        $delta = 0; $outcome = ''; $dice = $false
        if ($v -ge 4 -and $m -eq '大') { $delta = 1; $outcome = '赢 +1×注'; $dice = $true }
        elseif ($v -le 3 -and $m -eq '小') { $delta = 1; $outcome = '赢 +1×注'; $dice = $true }
        elseif (($v % 2 -eq 1) -and $m -eq '奇') { $delta = 1; $outcome = '赢 +1×注'; $dice = $true }
        elseif (($v % 2 -eq 0) -and $m -eq '偶') { $delta = 1; $outcome = '赢 +1×注'; $dice = $false }
        elseif ($m -eq '猜' -and $v -eq 3) { $delta = 5; $outcome = '赢 +5×注'; $dice = $false }
        else { $delta = -1; $outcome = '输 -1×注'; $dice = $false }
        $matrix += [pscustomobject]@{
            模式 = $m; 骰子值 = $v; 余额变化 = if ($delta -gt 0) { "+${delta}×注" } else { "-1×注" }
            发骰子动画 = if ($dice) { '是' } else { '否(缺陷)' }; 结果 = $outcome
        }
    }
}
$matrix | Format-Table -AutoSize | Out-String -Width 120 | Write-Output

Write-Output "==================== 五、赌徒破产推演（零期望 + 有限本金）===================="
Write-Output "假设：本金 1000，每局押 100（10%），押大，跑 1000 次实验"
$ruinCount = 0; $experiments = 1000; $rounds = 200; $balSum = 0
for ($e = 0; $e -lt $experiments; $e++) {
    $bal = 1000
    for ($r = 0; $r -lt $rounds; $r++) {
        if ($bal -lt 100) { break }
        $res = Invoke-Dufang -stone $bal -price 100 -mode '大'
        $bal = $res.stone
    }
    $balSum += $bal
    if ($bal -lt 100) { $ruinCount++ }
}
Write-Output ("200 局内破产（余额<100 无法继续）概率: {0:P1}" -f ($ruinCount / $experiments))
Write-Output ("平均剩余余额: {0:N0}（起始 1000）" -f ($balSum / $experiments))
