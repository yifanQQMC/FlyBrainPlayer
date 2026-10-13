"""阶梯加压测优化版上限（纯标准库）

优化点：
  1. 同类扫描 20 tick 节流（消除 O(N²)）
  2. BrainDebug 只在 DEBUG 级别输出（消除日志 I/O）
  3. MobCategory.MISC（解除 mob cap）
  4. 分散到多区块（单区块上限约 380）
"""
import math
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon

LOG = "runs/server/logs/latest.log"


def make_counter(r):
    r.cmd("scoreboard objectives add fbpcount dummy")

    def cnt():
        r.cmd("scoreboard players reset #t fbpcount")
        r.cmd("execute as @e[type=flybrainplayer:brain_player] "
              "run scoreboard players add #t fbpcount 1")
        out = r.cmd("scoreboard players get #t fbpcount")
        if "none is set" in out or "Unknown" in out:
            return 0
        for tok in out.replace(",", "").split():
            if tok.isdigit():
                return int(tok)
        return 0
    return cnt


def lag():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for l in f if "Can't keep up" in l)
    except OSError:
        return -1


def main():
    r = Rcon(timeout=600)
    cnt = make_counter(r)

    # 铺一块大台：200x200，分散到 13x13 个区块
    print("铺台（200x200，约 169 个区块）...")
    r.cmd("forceload add -100 -100 100 100")
    t0 = time.time()
    r.cmd("fill -100 61 -100 100 61 100 minecraft:stone")
    r.cmd("fill -100 62 -100 100 70 100 minecraft:air")
    r.cmd("gamerule doMobSpawning false")
    r.cmd("gamerule maxEntityCramming 0")
    r.cmd("difficulty peaceful")
    print(f"  铺台耗时 {time.time()-t0:.1f}s")

    # 阶梯加压
    levels = [500, 1000, 2000, 4000, 8000, 16000, 32000]
    print(f"\n{'目标':>7} {'实际':>7} {'响应ms':>8} {'lag数':>7}")
    print("-" * 40)

    for target in levels:
        t0 = time.time()
        base = cnt()
        need = target - base
        if need > 0:
            # 网格铺开，每格间距 4，确保跨区块
            for i in range(need):
                idx = base + i
                x = -100 + (idx % 50) * 4
                z = -100 + (idx // 50) * 4
                x = max(-100, min(100, x))
                z = max(-100, min(100, z))
                r.cmd(f"summon flybrainplayer:brain_player {x} 61 {z}")
        spawn_dt = time.time() - t0

        time.sleep(15)                     # 让 tick 稳定
        n = cnt()
        # 主线程延迟（唯一可靠的 tick 健康度指标）
        lat = sorted(
            (lambda: (lambda t0: (r.cmd("time query daytime"), time.time() - t0)[1])(time.time()))()
            for _ in range(3)
        )
        ms = lat[1] * 1000
        print(f"{target:>7} {n:>7} {ms:>8.0f} {lag():>7}   (召唤 {spawn_dt:.1f}s)")
        if ms > 1000:
            print(f"\n!! 主线程延迟 {ms:.0f}ms，过载，停止")
            break

    print(f"\n最终: {cnt()} 只, lag 警告 {lag()}")
    r.close()


if __name__ == "__main__":
    main()
