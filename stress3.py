"""渐进式加压：每批 200，等主线程延迟恢复再加下一批

目的：找到**可持续运行**的实体数上限（而非召唤瞬时峰值）。
上一版一次塞 2000 个导致 27 秒卡顿，那是瞬时峰值不是稳定上限。
"""
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
        out = r.cmd("scoreboard players get #t fbpcount")
        if "none is set" in out or "Unknown" in out:
            return 0
        for tok in out.replace(",", "").split():
            if tok.isdigit():
                return int(tok)
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

    r.cmd("forceload add -100 -100 100 100")
    r.cmd("fill -100 61 -100 100 61 100 minecraft:stone")
    r.cmd("fill -100 62 -100 100 70 100 minecraft:air")
    r.cmd("gamerule doMobSpawning false")
    r.cmd("gamerule maxEntityCramming 0")
    r.cmd("difficulty peaceful")
    print("OK  stage ready (200x200)")

    BATCH = 200
    MAX = 12000
    print(f"\n渐进加压：每批 {BATCH}，等延迟降到 300ms 以下再继续\n")
    print(f"{'累计':>7} {'延迟ms':>8} {'批次耗时':>9}")
    print("-" * 32)

    total = cnt()
    while total < MAX:
        t0 = time.time()
        for i in range(BATCH):
            idx = total + i
            x = -100 + (idx % 50) * 4
            z = -100 + (idx // 50) * 4
            x = max(-100, min(100, x))
            z = max(-100, min(100, z))
            r.cmd(f"summon flybrainplayer:brain_player {x} 61 {z}")
        batch_dt = time.time() - t0

        # 等 tick 稳定（最多等 60 秒）
        for _ in range(60):
            time.sleep(1)
            ms = latency(r, 1)
            if ms < 300:
                break

        total = cnt()
        ms = latency(r)
        print(f"{total:>7} {ms:>8.0f} {batch_dt:>8.1f}s")

        if ms > 2000:
            print(f"\n!! 延迟 {ms:.0f}ms 无法恢复，已达可持续上限")
            break

    print(f"\n最终稳定运行: {cnt()} 只，延迟 {latency(r):.0f}ms")
    r.close()


if __name__ == "__main__":
    main()
