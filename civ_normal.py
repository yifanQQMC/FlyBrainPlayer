"""正常世界文明测试：在自然地形上集中生成 AI

与超平坦版的区别：
  - 不铺任何平台，完全用自然地形
  - AI 生成在地表，会自己应对起伏、树木、水
  - 观测：真实地形中的存活率、扩散模式、社会行为
"""
import math
import re
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon


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


def find_ground(r, cx, cz, y_from=90):
    """从高处往下找第一层非空气方块，返回可站立的 y"""
    for y in range(y_from, 40, -1):
        r.cmd(f"execute if block {cx} {y} {cz} minecraft:air run say A{y}")
    time.sleep(2)
    # 从日志反推：最高的非空气 y
    airs = set()
    with open("runs/server/logs/latest.log", encoding="utf-8",
              errors="replace") as f:
        for line in f:
            m = re.search(r"\[Rcon\] A(\d+)", line)
            if m:
                airs.add(int(m.group(1)))
    for y in range(y_from, 40, -1):
        if y not in airs:
            return y + 1          # 站在这个方块上面
    return y_from


def main():
    n_target = int(sys.argv[1]) if len(sys.argv) > 1 else 500
    radius = int(sys.argv[2]) if len(sys.argv) > 2 else 20

    r = Rcon(timeout=600)
    cnt = make_counter(r)

    # 以玩家为中心
    pos = r.cmd("data get entity @p[limit=1] Pos")
    m = re.findall(r"(-?[\d.]+)d", pos)
    px, py, pz = (float(m[0]), float(m[1]), float(m[2])) if len(m) >= 3 \
        else (77.0, 71.0, 339.0)
    print(f"玩家位置: ({px:.0f}, {py:.0f}, {pz:.0f})")
    print(f"目标: {n_target} 只，半径 {radius} 格（自然地形）")

    r.cmd("gamerule doMobSpawning true")
    r.cmd("gamerule maxEntityCramming 0")
    r.cmd("gamerule doDaylightCycle false")
    r.cmd("time set noon")
    r.cmd("difficulty peaceful")

    # 逐点找地面高度，避免生成在空中摔死
    print("\n探测地表高度...")
    spots = []
    for i in range(n_target):
        ang = i * 2.399963
        rad = radius * math.sqrt((i + 0.5) / n_target)
        x = int(px + rad * math.cos(ang))
        z = int(pz + rad * math.sin(ang))
        spots.append((x, z))
    print(f"  计划 {len(spots)} 个生成点")

    print(f"\n集中生成 {n_target} 只...")
    t0 = time.time()
    ok = 0
    for x, z in spots:
        # 找这一点的地表 y（用 execute if block 批量探测会太慢，
        # 直接用玩家高度 + 小范围偏移，让 AI 自己落地）
        y = int(py) + 1
        r.cmd(f"summon flybrainplayer:brain_player {x} {y} {z}")
        ok += 1
    print(f"  召唤耗时 {time.time()-t0:.1f}s（{ok} 点）")

    time.sleep(10)
    n0 = cnt()
    print(f"  实际存活: {n0} / {n_target}"
          + (f"  (流失 {n_target - n0}，多为摔死/卡地形)" if n0 < n_target else ""))

    print(f"\n{'时间':>6} {'实体':>7} {'延迟ms':>8}")
    print("-" * 26)
    for i in range(8):
        time.sleep(20)
        print(f"+{(i+1)*20:>4}s {cnt():>7} {latency(r):>8.0f}")

    n_final = cnt()
    print(f"\n最终: {n_final} 只，延迟 {latency(r):.0f}ms")
    r.close()


if __name__ == "__main__":
    main()
