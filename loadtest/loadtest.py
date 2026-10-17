# -*- coding: utf-8 -*-
"""
iLink 真实 MySQL 环境压测脚本（Python 3.13 标准库实现，无第三方依赖）
用法：
  python loadtest.py single --path /home --concurrency 50 --duration 20
  python loadtest.py suite --cookie "JSESSIONID=xxx"
输出：每档并发一行 JSON 结果（打印到 stdout，同时追加到 results.jsonl）
"""
import argparse
import http.client
import json
import os
import statistics
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = 8091
RESULT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results.jsonl")


class Worker(threading.Thread):
    def __init__(self, path, headers, deadline, shared):
        super().__init__(daemon=True)
        self.path = path
        self.headers = headers
        self.deadline = deadline
        self.shared = shared  # dict: latencies[], codes{}, lock, stop

    def run(self):
        conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
        while time.perf_counter() < self.deadline:
            t0 = time.perf_counter()
            ok = True
            try:
                conn.request("GET", self.path, headers=self.headers)
                resp = conn.getresponse()
                resp.read()
                status = resp.status
            except Exception:
                status = "ERR"
                ok = False
            dt = (time.perf_counter() - t0) * 1000.0
            with self.shared["lock"]:
                self.shared["latencies"].append(dt)
                self.shared["codes"][str(status)] = self.shared["codes"].get(str(status), 0) + 1
            if not ok:
                try:
                    conn.close()
                except Exception:
                    pass
                conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
        try:
            conn.close()
        except Exception:
            pass


def run_one(name, path, headers, concurrency, duration):
    shared = {"latencies": [], "codes": {}, "lock": threading.Lock()}
    deadline = time.perf_counter() + duration
    workers = [Worker(path, headers, deadline, shared) for _ in range(concurrency)]
    t_start = time.perf_counter()
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    elapsed = time.perf_counter() - t_start

    lat = sorted(shared["latencies"])
    n = len(lat)

    def pct(p):
        if not lat:
            return 0.0
        idx = min(int(n * p / 100.0), n - 1)
        return lat[idx]

    result = {
        "scenario": name,
        "path": path,
        "concurrency": concurrency,
        "requests": n,
        "rps": round(n / elapsed, 1) if elapsed > 0 else 0.0,
        "p50_ms": round(pct(50), 1),
        "p90_ms": round(pct(90), 1),
        "p95_ms": round(pct(95), 1),
        "p99_ms": round(pct(99), 1),
        "max_ms": round(lat[-1], 1) if lat else 0.0,
        "mean_ms": round(statistics.fmean(lat), 1) if lat else 0.0,
        "codes": shared["codes"],
        "duration_s": round(elapsed, 1),
    }
    with open(RESULT_FILE, "a", encoding="utf-8") as f:
        f.write(json.dumps(result, ensure_ascii=False) + "\n")
    print(json.dumps(result, ensure_ascii=False))
    return result


def percentile_table(results):
    print("\n%-28s %6s %8s %8s %8s %8s %10s" % ("scenario", "conc", "p50", "p95", "p99", "max", "rps"))
    for r in results:
        print("%-28s %6d %8.1f %8.1f %8.1f %8.1f %10.1f" % (
            r["scenario"][:28], r["concurrency"], r["p50_ms"], r["p95_ms"], r["p99_ms"], r["max_ms"], r["rps"]))


def do_suite(args):
    cookie_header = {"Cookie": args.cookie} if args.cookie else {}
    scenarios = [
        ("A_login_page", "/login", {}),
        ("B_posts_list", "/api/community/posts?page=1&size=10", {}),
        ("C_competitions", "/api/competitions?page=1&size=10", {}),
        ("D_teacher_list", "/api/teacher/list", {}),
        ("E_notifications_auth", "/api/notifications?limit=10", cookie_header),
        ("F_team_list_auth", "/api/team/list", cookie_header),
    ]
    levels = [int(x) for x in args.levels.split(",")]
    # 先预热：JIT/缓存/连接池
    print("== 预热 5s（concurrency=4）==")
    for name, path, extra in scenarios:
        run_one("warmup_" + name, path, {**cookie_header, **extra}, 4, 5)
    results = []
    for conc in levels:
        print(f"\n== 并发 {conc} ==")
        for name, path, extra in scenarios:
            results.append(run_one(name, path, {**cookie_header, **extra}, conc, args.duration))
    percentile_table(results)


def do_single(args):
    headers = {}
    if args.cookie:
        headers["Cookie"] = args.cookie
    results = []
    levels = [int(x) for x in args.levels.split(",")] if args.levels else [1]
    for conc in levels:
        results.append(run_one(args.name or os.path.basename(args.path), args.path, headers, conc, args.duration))
    percentile_table(results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("suite")
    p1.add_argument("--cookie", default="")
    p1.add_argument("--duration", type=int, default=15)
    p1.add_argument("--levels", default="10,50,100,200")
    p2 = sub.add_parser("single")
    p2.add_argument("--path", required=True)
    p2.add_argument("--name", default="")
    p2.add_argument("--cookie", default="")
    p2.add_argument("--duration", type=int, default=15)
    p2.add_argument("--levels", default="1,10")
    args = parser.parse_args()
    if os.path.exists(RESULT_FILE) and args.cmd == "suite":
        os.remove(RESULT_FILE)
    {"suite": do_suite, "single": do_single}[args.cmd](args)
