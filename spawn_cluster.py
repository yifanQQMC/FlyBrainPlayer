"""集中扎堆生成测试：在一个小范围内密集生成 AI，测高密度社会行为

与平台网格/自然生成的区别：
  - 不铺平台，就在真实地形的一个点周围
  - 范围极小（默认 12x12 = 144 格），故意让 AI 挤在一起
  - 观测：扎堆时的社交/攻击/逃跑行为、实体流失率、服务端压力

用法:
    python spawn_cluster.py 200            # 集中 200 只
    python spawn_cluster.py 400 --radius 8 # 半径 8 格
"""
import math
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon

RADIUS = 12
LOG = "runs/server/logs/latest.log"


def make_counter(r):
    r.cmd("scoreboard objectives add fbpcount dummy")

    def cnt():
        r.cmd("scoreboard players reset #t fbpcount")
        r.cmd("execute as @e[type=flybrainplayer:brain_player] "
              "run scoreboard players add #t fbpcount 1")
        o = r.cmd("scoreboard players get #t fbpcount")
        if "none is set" in o:
            return 0
        for t in o.replace(",", "").split():
            if t.isdigit():
                return int(t)
        return 0
    return cnt


def latency(r, samples=3):
    lat = []
    for _ in range(samples):
        t0 = time.time()
        r.cmd("time query daytime")
        lat.append((time.time() - t0) * 1000)
    lat.sort()
    return lat[len(lat) // 2]


def skull_count(r):
    """统计各头颅数量 —— 用自定义名里的颜色码区分"""
    out = {}
    r.cmd("scoreboard objectives add fbpskull dummy")
    for tag in ["balanced", "brute", "sniper", "volatile",
                "scholar", "artisan", "wither", "forager"]:
        r.cmd(f"scoreboard players reset #s_{tag} fbpskull")
        r.cmd(f'execute as @e[type=flybrainplayer:brain_player,'
              f'name=!"#"] run scoreboard players add #s_{tag} fbpskull 1')
        # name 选择器不好按 tag 过滤，改用 data get 太慢；
        # 简化：只统计总数，头颅分布在最终快照时读
    return out


def main():
    n_target = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    radius = int(sys.argv[2]) if len(sys.argv) > 2 else RADIUS

    r = Rcon(timeout=600)
    cnt = make_counter(r)

    # 找玩家位置作为聚集中心（跟随玩家，不是固定原点）
    r.cmd("forceload add -3 -3 3 3")
    print("OK  forceload 3x3")

    # 中心点：用玩家所在位置（如果没有玩家就用 0,61,0）
    pos_out = r.cmd("data get entity @p[limit=1] Pos")
    cx, cz = 0, 0
    try:
        import re
        m = re.findall(r"(-?[\d.]+)d", pos_out)
        if len(m) >= 2:
            cx, cz = int(float(m[0])), int(float(m[2]))
    except Exception:
        pass
    print(f"聚集中心: ({cx}, {cz})")

    # 在地面上方集中生成（y=玩家所在 y + 1）
    try:
        m = re.findall(r"(-?[\d.]+)d", pos_out)
        cy = int(float(m[1])) + 1 if len(m) >= 3 else 62
    except Exception:
        cy = 62

    print(f"\n集中生成 {n_target} 只（半径 {radius} 格，中心 y={cy}）...")
    t0 = time.time()
    for i in range(n_target):
        # 在圆内均匀随机分布（用固定种子保证可复现）
        ang = i * 2.399963
        rad = radius * math.sqrt((i + 0.5) / n_target)
        x = cx + int(rad * math.cos(ang))
        z = cz + int(rad * math.sin(ang))
        r.cmd(f"summon flybrainplayer:brain_player {x} {cy} {z}")
    print(f"  召唤耗时 {time.time()-t0:.1f}s")

    time.sleep(8)
    n0 = cnt()
    print(f"  实际生成: {n0} / {n_target}"
          + (f"  (流失 {n_target - n0})" if n0 < n_target else ""))

    print(f"\n{'时间':>6} {'实体':>7} {'延迟ms':>8}")
    print("-" * 26)
    for i in range(8):
        time.sleep(20)
        print(f"+{(i+1)*20:>4}s {cnt():>7} {latency(r):>8.0f}")

    n_final = cnt()
    print(f"\n最终: {n_final} 只，延迟 {latency(r):.0f}ms")
    if n_final < n0:
        print(f"  → 流失 {n0 - n_final} 只（扎堆挤压/互相攻击/掉坑）")
    r.close()


if __name__ == "__main__":
    main()
