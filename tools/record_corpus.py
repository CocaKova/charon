#!/usr/bin/env python3
"""Record terminal byte streams for the terminal-core golden corpus.

Spawns a command in a pty with a fixed window size (default 80x24,
TERM=xterm-256color), optionally feeds scripted keystrokes with delays, and dumps
every byte the program writes to a .bin file under
terminal-core/src/test/resources/corpus/.

Usage:
  record_corpus.py OUT.bin [--cols 80] [--rows 24] [--keys "SPEC"] -- CMD [ARGS…]

Key spec: segments separated by commas. "sleep:N" waits N seconds (float), anything
else is sent literally with escape support (\\r, \\x1b, …). Example:
  --keys "sleep:2,:q\\r,sleep:0.5"
"""

import argparse
import codecs
import fcntl
import os
import pty
import select
import struct
import sys
import termios
import time


def set_winsize(fd, rows, cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


def main():
    # Split at "--" ourselves: argparse.REMAINDER greedily eats options that appear
    # after the first positional, which silently breaks --keys.
    argv = sys.argv[1:]
    if "--" not in argv:
        sys.exit("usage: record_corpus.py OUT [options] -- CMD [ARGS…]")
    split = argv.index("--")
    cmd = argv[split + 1:]
    if not cmd:
        sys.exit("no command given after --")

    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--cols", type=int, default=80)
    ap.add_argument("--rows", type=int, default=24)
    ap.add_argument("--keys", default="")
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args(argv[:split])

    steps = []
    if args.keys:
        for seg in args.keys.split(","):
            if seg.startswith("sleep:"):
                steps.append(("sleep", float(seg[6:])))
            else:
                steps.append(("send", codecs.decode(seg, "unicode_escape").encode()))

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.environ["COLUMNS"] = str(args.cols)
        os.environ["LINES"] = str(args.rows)
        os.execvp(cmd[0], cmd)

    set_winsize(fd, args.rows, args.cols)
    recorded = bytearray()
    deadline = time.time() + args.timeout
    step_at = time.time()
    step_i = 0

    while time.time() < deadline:
        # run scheduled key steps
        while step_i < len(steps):
            kind, val = steps[step_i]
            if kind == "sleep":
                if time.time() - step_at < val:
                    break
                step_at = time.time()
                step_i += 1
            else:
                try:
                    os.write(fd, val)
                except OSError:
                    pass
                step_at = time.time()
                step_i += 1

        r, _, _ = select.select([fd], [], [], 0.05)
        if fd in r:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                break
            if not chunk:
                break
            recorded.extend(chunk)
        else:
            if step_i >= len(steps):
                # Scripted input exhausted and output is quiet: stop here. Full-screen
                # apps are deliberately killed mid-screen so the golden captures their
                # UI, not their exit-time alt-screen restore.
                break
            done, _ = os.waitpid(pid, os.WNOHANG)
            if done == pid:
                break

    try:
        os.kill(pid, 15)
    except ProcessLookupError:
        pass

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(bytes(recorded))
    print(f"{args.out}: {len(recorded)} bytes")


if __name__ == "__main__":
    main()
