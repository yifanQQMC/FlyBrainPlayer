"""FlyBrainPlayer 批量召唤 + 承载压力测试（纯标准库）

用法:
    python spawn_batch.py 100              # 召唤 100 只，默认 10 个一批
    python spawn_batch.py 100 --batch 5    # 5 个一批（更安全）
    python spawn_batch.py 100 --mix        # 螺旋分布 + 混合头颅（文明测试用）
    python spawn_batch.py --status         # 只看当前状态

设计要点（踩过坑）:
    1. **绝不能一次 summon 几十万个** —— 服务端会在建实体阶段 OOM 或 tick 爆炸。
       必须分批，每批之间让服务端喘口气。
    2. **无玩家时实体不 tick** —— 必须先 forceload，否则召唤出来的全是"植物人"。
    3. 实体数用 scoreboard 统计（@e 选择器有上限，数不准）。
    4. TPS 从日志的 "Can't keep up" 警告间接判断。
    5. RCON 握手直接复用 rcon.py 的 Rcon 类 —— 那个的多包响应读取是实测对的。
       自己重写会多吃一个不存在的确认包导致卡死。
"""
import argparse
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon          # 复用实测可用的握手实现

AREA_MIN, AREA_MAX = -50, 50
GROUND_Y = 61
LOG = "/d/Projects/FlyBrainPlayer/runs/server/logs/latest.log"


# ---------- 观测 ----------
def count_entities(r):
    """用 scoreboard 精确统计实体数"""
    r.cmd("scoreboard objectives add fbpcount dummy")
    r.cmd("scoreboard players reset #total fbpcount")
    r.cmd("execute as @e[type=flybrainplayer:brain_player] "
          "run scoreboard players add #total fbpcount 1")
    out = r.cmd("scoreboard players get #total fbpcount")
    for tok in out.replace(",", "").split():
        if tok.isdigit():
            return int(tok)
    return 0


def lag_warnings():
    """日志里 "Can't keep up" 的总次数"""
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for l in f if "Can't keep up" in l)
    except OSError:
        return -1


def worst_tick_ms():
    """日志里出现过的最长 tick 耗时"""
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            best = 0
            for l in f:
                if "Running " in l and "ms or" in l:
                    try:
                        best = max(best, int(l.split("Running ")[1].split("ms")[0]))
                    except (IndexError, ValueError):
                        pass
            return best
    except OSError:
        return -1


def setup_stage(r):
    """铺一块测试台 + forceload + 关掉干扰项"""
    r.cmd("forceload add -16 -16 16 16")
    r.cmd(f"fill {AREA_MIN} {GROUND_Y} {AREA_MIN} {AREA_MAX} {GROUND_Y} "
          f"{AREA_MAX} minecraft:stone")
    r.cmd(f"fill {AREA_MIN} {GROUND_Y+1} {AREA_MIN} {AREA_MAX} {GROUND_Y+6} "
          f"{AREA_MAX} minecraft:air")
    r.cmd("gamerule doMobSpawning false")
    r.cmd("gamerule mobGriefing false")
    r.cmd("difficulty peaceful")


# ---------- 主流程 ----------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("count", nargs="?", type=int, default=0)
    ap.add_argument("--batch", type=int, default=10)
    ap.add_argument("--mix", action="store_true", help="螺旋分布，交错排列")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--y", type=int, default=GROUND_Y)
    args = ap.parse_args()

    r = Rcon(timeout=120)
    print("OK  RCON connected")

    setup_stage(r)
    n0 = count_entities(r)
    print(f"OK  stage ready, entities now: {n0}")

    if args.status:
        print(f"    lag warnings: {lag_warnings()}   worst tick: {worst_tick_ms()}ms")
        r.close()
        return

    total = args.count
    print(f"\nSpawning {total} (batch {args.batch})...")
    print(f"{'#':>4} {'cum':>7} {'total':>7} {'sec':>8} {'lag':>7}")
    print("-" * 44)

    t_all = time.time()
    for i in range(0, total, args.batch):
        n_this = min(args.batch, total - i)
        t0 = time.time()
        for k in range(n_this):
            idx = i + k
            if args.mix:
                ang = idx * 2.399963            # 向日葵螺旋
                rad = 3.0 * math.sqrt(idx + 1)
                x = int(rad * math.cos(ang))
                z = int(rad * math.sin(ang))
            else:
                side = max(1, int(math.sqrt(total)))
                gx, gz = idx % side, idx // side
                x, z = AREA_MIN + 2 + gx * 3, AREA_MIN + 2 + gz * 3
            x = max(AREA_MIN, min(AREA_MAX, x))
            z = max(AREA_MIN, min(AREA_MAX, z))
            r.cmd(f"summon flybrainplayer:brain_player {x} {args.y} {z}")

        dt = time.time() - t0
        print(f"{i//args.batch + 1:>4} {i+n_this:>7} {count_entities(r):>7} "
              f"{dt:>7.2f}s {lag_warnings():>7}")
        if i + args.batch < total:
            time.sleep(0.3)

    dt_all = time.time() - t_all
    final = count_entities(r)
    print("-" * 44)
    print(f"\nDONE  {final} entities, {dt_all:.1f}s")
    print(f"      worst tick: {worst_tick_ms()}ms (normal <= 50ms)")
    print(f"      lag warnings: {lag_warnings()}")

    for wait in (15, 30):
        time.sleep(wait)
        f2 = count_entities(r)
        tag = "  <-- decreasing!" if f2 < final else ""
        print(f"      +{wait}s: {f2} entities{tag}")

    r.close()


if __name__ == "__main__":
    main()
