"""阶梯加压测试：找出服务器能稳定承载多少 AI 玩家

用法: python stress_test.py [起始数] [每级增量] [级数]
      python stress_test.py 200 200 8      # 200→400→...→1600

每级做：
  1. 召唤到目标数
  2. 等 20 秒让 tick 稳定
  3. 采样：实体数 / 新增 lag 警告 / 最长 tick / 服务端响应延迟
响应延迟是**最灵敏的指标** —— tick 一超时，RCON 命令立刻变慢。
"""
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon

LOG = "runs/server/logs/latest.log"
SKULLS = ["balanced", "brute", "sniper", "volatile",
          "scholar", "artisan", "wither", "forager"]


def count(r):
    r.cmd("scoreboard objectives add fbpcount dummy")
    r.cmd("scoreboard players reset #t fbpcount")
    r.cmd("execute as @e[type=flybrainplayer:brain_player] "
          "run scoreboard players add #t fbpcount 1")
    out = r.cmd("scoreboard players get #t fbpcount")
    for tok in out.replace(",", "").split():
        if tok.isdigit():
            return int(tok)
    return 0


def lag():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for l in f if "Can't keep up" in l)
    except OSError:
        return -1


def worst_tick():
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


def ping(r):
    """主线程排队延迟 —— **唯一可靠的 tick 健康度指标**

    坑：用 `list` 或实体计数当 ping 是错的。
      - `list` 不走主线程，永远 1ms，测不出过载
      - 实体计数要遍历所有实体，自身就 100ms+，会把测量成本算进去
    `time query` 必须排到主线程执行，延迟直接反映 tick 积压程度。
    正常 <50ms；>200ms 明显卡；>1000ms 严重过载。
    """
    t0 = time.time()
    r.cmd("time query daytime")
    return (time.time() - t0) * 1000


def spawn_to(r, target, mix=False):
    """补召到 target 只，螺旋分布"""
    have = count(r)
    if have >= target:
        return have
    import math
    n_new = target - have
    for i in range(n_new):
        idx = have + i
        if mix:
            ang = idx * 2.399963
            rad = 3.0 * math.sqrt(idx + 1)
            x, z = int(rad * math.cos(ang)), int(rad * math.sin(ang))
        else:
            side = max(1, int(target ** 0.5))
            x = -50 + 2 + (idx % side) * 3
            z = -50 + 2 + (idx // side) * 3
        x = max(-50, min(50, x))
        z = max(-50, min(50, z))
        r.cmd(f"summon flybrainplayer:brain_player {x} 61 {z}")
    return count(r)


def main():
    start = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    step = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    levels = int(sys.argv[3]) if len(sys.argv) > 3 else 6
    mix = "--mix" in sys.argv

    r = Rcon(timeout=300)
    print(f"阶梯加压: {start} → {start + step * (levels - 1)}，"
          f"每级 +{step}，混合头颅={'是' if mix else '否'}\n")

    print(f"{'目标':>7} {'实际':>7} {'响应ms':>8} {'lag数':>7} "
          f"{'最长tick':>9} {'判定':>6}")
    print("-" * 56)

    results = []
    for lv in range(levels):
        target = start + step * lv
        spawn_to(r, target, mix)
        time.sleep(20)                      # 让 tick 稳定
        n = count(r)
        # 多次采样取中位数，避免单次抖动误判
        samples = sorted(ping(r) for _ in range(5))
        ms = samples[len(samples) // 2]
        lg = lag()
        wt = worst_tick()
        verdict = "OK" if ms < 200 else ("卡" if ms < 1000 else "崩")
        print(f"{target:>7} {n:>7} {ms:>8.0f} {lg:>7} {wt:>8}ms {verdict:>6}")
        results.append((target, n, ms, lg, wt))
        if ms > 1000:
            print(f"\n!! 主线程延迟 {ms:.0f}ms，服务端已严重过载，停止加压")
            break

    print("\n" + "=" * 56)
    ok = [x for x in results if x[2] < 200]
    if ok:
        best = max(ok, key=lambda x: x[1])
        print(f"✓ 可稳定运行: {best[1]} 只（响应 {best[2]:.0f}ms）")
    print(f"✗ 过载临界点: 约 {results[-1][1]} 只（响应 {results[-1][2]:.0f}ms）")
    print(f"\n最终 lag 警告总数: {lag()}，最长 tick: {worst_tick()}ms")

    r.close()


if __name__ == "__main__":
    main()
