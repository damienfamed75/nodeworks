#!/usr/bin/env python3
"""Watch the dev-client's structure save directory and auto-convert `.nbt` to `.snbt`.

Flow: the user saves a structure in-game via a structure block → the `.nbt` lands in
`neoforge/run/saves/<world>/generated/nodeworks/structure/` → this watcher sees it and
runs `scripts/nbt-to-snbt.py` to produce a GuideME-compatible `.snbt` at
`guidebook/assets/assemblies/<name>.snbt`. Paired with live-preview
(`./gradlew :neoforge:runClient`), that means: save a structure in-game and it's already
in the guide on the next time the page reloads.

Usage:
    python scripts/watch-structures.py

Runs until Ctrl+C. Requires `watchdog` + `nbtlib` (both installed via pip).
"""

import subprocess
import sys
import time
from pathlib import Path
from threading import Timer

try:
    from watchdog.observers import Observer
    from watchdog.events import FileSystemEventHandler
except ImportError:
    print("Install watchdog first: pip install watchdog", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
# Structure block saves land here (world name is "Structures" in the user's dev world).
# If you use a differently-named save, point this at your save's `generated/nodeworks/structure`.
WATCH_DIR = REPO_ROOT / "neoforge" / "run" / "saves" / "Structures" / "generated" / "nodeworks" / "structure"
OUTPUT_DIR = REPO_ROOT / "guidebook" / "assets" / "assemblies"
CONVERTER = REPO_ROOT / "scripts" / "nbt-to-snbt.py"

# Structure block saves often emit multiple filesystem events in quick succession
# (temp file write → rename → sometimes a follow-up touch). Wait for the burst to settle
# before converting so we don't read a half-written file.
DEBOUNCE_SECONDS = 0.5


class NbtHandler(FileSystemEventHandler):
    def __init__(self):
        self._timers: dict[str, Timer] = {}

    def on_created(self, event):
        if not event.is_directory and event.src_path.endswith(".nbt"):
            self._schedule(Path(event.src_path))

    def on_modified(self, event):
        if not event.is_directory and event.src_path.endswith(".nbt"):
            self._schedule(Path(event.src_path))

    def _schedule(self, src: Path):
        key = str(src)
        if key in self._timers:
            self._timers[key].cancel()
        t = Timer(DEBOUNCE_SECONDS, lambda: self._convert(src))
        self._timers[key] = t
        t.start()

    def _convert(self, src: Path):
        try:
            dest = OUTPUT_DIR / (src.stem + ".snbt")
            OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(
                [sys.executable, str(CONVERTER), str(src), str(dest)],
                capture_output=True, text=True,
            )
            rel_dest = dest.relative_to(REPO_ROOT).as_posix()
            if result.returncode == 0:
                print(f"[OK]  {src.name}  ->  {rel_dest}")
            else:
                err = (result.stderr.strip() or result.stdout.strip()) or f"exit {result.returncode}"
                print(f"[ERR] {src.name}: {err}")
        except Exception as e:
            print(f"[ERR] {src.name}: {e}")


def main():
    # Observer can't watch a non-existent directory; create it pre-emptively so the script
    # still works before the user has saved their first structure.
    WATCH_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Watching: {WATCH_DIR}")
    print(f"Output:   {OUTPUT_DIR}")
    print("Save a structure in-game via a structure block to trigger a conversion.")
    print("Press Ctrl+C to stop.\n")

    observer = Observer()
    observer.schedule(NbtHandler(), str(WATCH_DIR), recursive=False)
    observer.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping watcher.")
        observer.stop()
    observer.join()


if __name__ == "__main__":
    main()
