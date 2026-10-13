"""文明测试 v3：完整前置 + 部署 + 观测

修正前三次失败：
  1. forceload 只加 -7..6（`add -100 -100 100 100` 被拒）→ 范围限 -112..111
  2. fill 单条有方块上限 → 分块 64x64
  3. 出生日志刷屏堵死 tick → 已改为 DEBUG 级别
  4. doTalk/Sensors 的 O(N²) 同类扫描 → 已加 20 tick 节流
"""
import math
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon

N = 1500
RADIUS = 100


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


def main():
    r = Rcon(timeout=600)
    cnt = make_counter(r)

    # 1. forceload（只加一次，用服务端接受的范围）
    r.cmd("forceload add -7 -7 6 6")
    print("OK  forceload -7..6 (196 chunks)")

    # 2. 分块铺台（64x64 = 4096 方块/块，避开 fill 上限）
    t0 = time.time()
    n = 0
    for bx in range(-112, 112, 64):
        for bz in range(-112, 112, 64):
            x2 = min(bx + 63, 111)
            z2 = min(bz + 63, 111)
            r.cmd(f"fill {bx} 61 {bz} {x2} 61 {z2} minecraft:stone")
            n += 1
    for bx in range(-112, 112, 64):
        for bz in range(-112, 112, 64):
            x2 = min(bx + 63, 111)
            z2 = min(bz + 63, 111)
            r.cmd(f"fill {bx} 62 {bz} {x2} 70 {z2} minecraft:air")
    print(f"OK  stage ({n} blocks, {time.time()-t0:.1f}s)")

    # 3. 规则
    r.cmd("gamerule doMobSpawning false")
    r.cmd("gamerule maxEntityCramming 0")
    r.cmd("gamerule doDaylightCycle false")
    r.cmd("time set noon")
    r.cmd("difficulty peaceful")

    # 4. 部署
    print(f"\n部署 {N} 只...")
    t0 = time.time()
    for i in range(N):
        ang = i * 2.399963
        rad = 3.0 * math.sqrt(i + 1)
        x = int(rad * math.cos(ang))
        z = int(rad * math.sin(ang))
        x = max(-RADIUS, min(RADIUS, x))
        z = max(-RADIUS, min(RADIUS, z))
        r.cmd(f"summon flybrainplayer:brain_player {x} 61 {z}")
    print(f"  召唤 {time.time()-t0:.1f}s，当前 {cnt()} 只")

    # 5. 观测
    print(f"\n{'时间':>6} {'实体':>7} {'延迟ms':>8}")
    print("-" * 26)
    for i in range(8):
        time.sleep(20)
        print(f"+{(i+1)*20:>4}s {cnt():>7} {latency(r):>8.0f}")

    print(f"\n最终: {cnt()} 只, 延迟 {latency(r):.0f}ms")
    r.close()


if __name__ == "__main__":
    main()
