#!/usr/bin/env python3
"""启动 Spring Boot 后执行子命令（简化版，供 Playwright 冒烟使用）。"""
import argparse
import socket
import subprocess
import sys
import time


def port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True, help="启动服务的 shell 命令")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("command", nargs=argparse.REMAINDER, help="服务就绪后执行的命令")
    args = parser.parse_args()

    if not args.command:
        print("usage: with_server.py --server CMD --port PORT -- command...", file=sys.stderr)
        return 2

    proc = subprocess.Popen(
        args.server,
        shell=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.time() + 120
        while time.time() < deadline:
            if proc.poll() is not None:
                print("Server process exited early", file=sys.stderr)
                return 1
            if port_open("127.0.0.1", args.port):
                break
            time.sleep(1)
        else:
            print(f"Timed out waiting for port {args.port}", file=sys.stderr)
            return 1

        return subprocess.call(args.command)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
