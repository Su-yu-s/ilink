# -*- coding: utf-8 -*-
"""并发 toggle-like 竞态测试：N 个线程同时以同一用户取消同一帖子的赞"""
import http.client
import json
import re
import sys
import threading
import time
from collections import Counter

HOST, PORT = "127.0.0.1", 8091


def load_cookie(path):
    sess, xsrf = None, None
    for line in open(path, encoding="utf-8"):
        parts = line.split("\t")
        if len(parts) >= 7:
            if parts[5] == "JSESSIONID":
                sess = parts[6].strip()
            if parts[5] == "XSRF-TOKEN":
                xsrf = parts[6].strip()
    return sess, xsrf


def race(post_id, cookie_file, n_threads):
    sess, xsrf = load_cookie(cookie_file)
    headers = {
        "Cookie": f"JSESSIONID={sess}; XSRF-TOKEN={xsrf}",
        "X-XSRF-TOKEN": xsrf,
        "Content-Type": "application/json",
    }
    barrier = threading.Barrier(n_threads)
    results = Counter()
    lock = threading.Lock()
    sample_errors = []

    def fire():
        try:
            conn = http.client.HTTPConnection(HOST, PORT, timeout=20)
            barrier.wait()  # 所有线程就位后同时开火
            conn.request("POST", f"/api/community/posts/{post_id}/like", body="{}", headers=headers)
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", "replace")
            with lock:
                results[resp.status] += 1
                if resp.status != 200 and len(sample_errors) < 3:
                    sample_errors.append(body[:300])
            conn.close()
        except Exception as e:
            with lock:
                results[f"EXC:{type(e).__name__}"] += 1

    t0 = time.perf_counter()
    threads = [threading.Thread(target=fire) for _ in range(n_threads)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - t0
    print(json.dumps({"post_id": post_id, "threads": n_threads, "elapsed_ms": round(elapsed * 1000),
                      "responses": dict(results), "error_samples": sample_errors}, ensure_ascii=False))


if __name__ == "__main__":
    race(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
