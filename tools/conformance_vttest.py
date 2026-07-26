#!/usr/bin/env python3
"""Drive vttest (or esctest) against the real terminal-core emulator.

Spawns the suite in an 80x24 pty and the ConformanceBridge JVM as the terminal on
the other end: pty output is fed to the emulator, emulator responses (DA/DSR/CPR/…)
are written back to the pty. Whenever output goes quiet the current grid is
snapshotted to a numbered file; if the screen shows a menu prompt the next scripted
choice is sent, otherwise <RETURN> advances to the next screen.

Usage:
  conformance_vttest.py OUTDIR --classpath-file CPFILE \
      [--choices "1,2,3,4,6,8,0"] [--timeout 240] -- CMD [ARGS…]

Review the snapshots by hand — they ARE the conformance evidence; keep the findings
in docs/TERMINAL.md's conformance log.
"""

import argparse
import base64
import fcntl
import os
import pty
import select
import struct
import subprocess
import sys
import termios
import time


def set_winsize(fd, rows, cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


def main():
    argv = sys.argv[1:]
    if "--" not in argv:
        sys.exit("usage: conformance_vttest.py OUTDIR [options] -- CMD [ARGS…]")
    split = argv.index("--")
    cmd = argv[split + 1:]

    ap = argparse.ArgumentParser()
    ap.add_argument("outdir")
    ap.add_argument("--classpath-file", required=True)
    ap.add_argument("--choices", default="1,2,3,4,6,8,0")
    ap.add_argument("--cols", type=int, default=80)
    ap.add_argument("--rows", type=int, default=24)
    ap.add_argument("--quiesce", type=float, default=0.7)
    ap.add_argument("--timeout", type=float, default=240.0)
    ap.add_argument("--max-screens", type=int, default=250)
    # Passive: never type; the suite under the pty drives itself (esctest). We only
    # pump bytes/responses and snapshot the final grid when it exits.
    ap.add_argument("--passive", action="store_true")
    args = ap.parse_args(argv[:split])

    os.makedirs(args.outdir, exist_ok=True)
    cp = open(args.classpath_file).read().strip()
    bridge = subprocess.Popen(
        ["java", "-cp", cp,
         "com.cocakova.charon.terminal.conformance.ConformanceBridge",
         str(args.cols), str(args.rows)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm"
        os.execvp(cmd[0], cmd)
    set_winsize(fd, args.rows, args.cols)

    choices = [c for c in args.choices.split(",") if c]
    log = open(os.path.join(args.outdir, "driver.log"), "w")
    snap_i = 0
    last_output = time.time()
    deadline = time.time() + args.timeout
    child_alive = True

    def bridge_cmd(line):
        bridge.stdin.write(line + "\n")
        bridge.stdin.flush()

    def pump_bridge_out(block_until_ok=None):
        """Read R/OK lines from the bridge; forward responses to the pty."""
        while True:
            if block_until_ok is None:
                r, _, _ = select.select([bridge.stdout], [], [], 0)
                if not r:
                    return
            line = bridge.stdout.readline()
            if not line:
                return
            line = line.rstrip("\n")
            if line.startswith("R "):
                try:
                    os.write(fd, base64.b64decode(line[2:]))
                except OSError:
                    pass
            elif line.startswith("OK ") and block_until_ok:
                return

    def dump(tag):
        nonlocal snap_i
        path = os.path.join(args.outdir, "snap_%03d.txt" % snap_i)
        bridge_cmd("DUMP " + path)
        pump_bridge_out(block_until_ok=path)
        snap_i += 1
        log.write("%s -> %s\n" % (tag, path))
        log.flush()
        return open(path).read()

    def send(keys, tag):
        log.write("send %r (%s)\n" % (keys, tag))
        log.flush()
        try:
            os.write(fd, keys.encode())
        except OSError:
            pass

    while time.time() < deadline and snap_i < args.max_screens:
        # Watch BOTH the pty and the bridge: emulator responses must reach the pty
        # the moment they're emitted, or the suite's report-reads time out and the
        # late reply gets echoed at the next prompt as if the user typed it.
        r, _, _ = select.select([fd, bridge.stdout], [], [], 0.05)
        if bridge.stdout in r:
            pump_bridge_out()
        if fd in r:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                chunk = b""
            if chunk:
                bridge_cmd("B " + base64.b64encode(chunk).decode())
                pump_bridge_out()
                last_output = time.time()
                continue
            child_alive = False
        if not child_alive:
            dump("final")
            break
        if args.passive or time.time() - last_output < args.quiesce:
            continue
        # Screen is quiet: snapshot, then decide how to advance.
        grid = dump("quiesce")
        if "Enter choice number" in grid or "Choose test type" in grid:
            nxt = choices.pop(0) if choices else "0"
            send(nxt + "\r", "menu choice")
        elif "Push <LF>" in grid:
            send("\n", "push-lf")
        else:
            send("\r", "advance")
        last_output = time.time()  # don't re-fire before the suite reacts

    try:
        os.kill(pid, 15)
    except ProcessLookupError:
        pass
    bridge_cmd("EXIT")
    bridge.stdin.close()
    bridge.wait(timeout=10)
    log.close()
    print("%d snapshots in %s" % (snap_i, args.outdir))


if __name__ == "__main__":
    main()
