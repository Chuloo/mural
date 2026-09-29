"""Publish a complete Play screenshot capture while preserving the previous set on failure."""
from pathlib import Path
from typing import Sequence
import os
import tempfile


def publish_capture_assets(output_root: Path, payloads: Sequence[tuple[Path, bytes]]) -> None:
    output_root.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='mural-play-stage-', dir=output_root.parent) as directory:
        staging = Path(directory)
        for index, (_, data) in enumerate(payloads):
            (staging / f'new-{index}').write_bytes(data)
        applied: list[tuple[Path, Path | None, bool]] = []
        try:
            for index, (relative, _) in enumerate(payloads):
                target = output_root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                backup = staging / f'old-{index}' if target.exists() or target.is_symlink() else None
                if backup is not None:
                    os.replace(target, backup)
                applied.append((target, backup, False))
                os.replace(staging / f'new-{index}', target)
                applied[-1] = (target, backup, True)
        except BaseException:
            for target, backup, installed in reversed(applied):
                if installed:
                    target.unlink(missing_ok=True)
                if backup is not None:
                    os.replace(backup, target)
            raise
