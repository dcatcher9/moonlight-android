#!/usr/bin/env python3
"""Fit static ZipDepth-to-host V2 coordinate scales from saved depth predictions.

The fit mirrors the production coordinate contract without changing production files:

    zip_coordinate = (zip_raw - zip_first_frame_mean) / fitted_scale
    host_coordinate = (area_resize(host_raw) - host_first_frame_mean) / 2.25

One through-origin least-squares scale is fitted for each ZipDepth graph shape. Every clip owns
its own first-frame arithmetic-mean shot center. The tool rejects missing pairs, non-finite data,
unexpected production graph shapes, and a non-positive ZipDepth/host coordinate correlation.
It writes only JSON to stdout.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any, Iterable

import numpy as np


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PREDICTION_ROOT = (
    REPOSITORY_ROOT
    / "build"
    / "client-sbs-model-eval"
    / "results"
    / "predictions"
)
DEFAULT_ZIPDEPTH_DIRECTORY = DEFAULT_PREDICTION_ROOT / "zipdepth_base_official384"
DEFAULT_HOST_DIRECTORY = DEFAULT_PREDICTION_ROOT / "host_hybrid_fused"
PRODUCTION_GRAPH_SHAPES = frozenset({(384, 672), (384, 896), (384, 928)})
HOST_V2_RAW_SCALE = 2.25


class CalibrationError(RuntimeError):
    """Raised when prediction inputs cannot produce an authenticated calibration."""


def _axis_area_resize(values: np.ndarray, output_size: int, axis: int) -> np.ndarray:
    """Exact source-cell area resize along one shrinking axis."""

    input_size = values.shape[axis]
    if output_size <= 0:
        raise CalibrationError("Area-resize output dimensions must be positive")
    if input_size < output_size:
        raise CalibrationError(
            f"Area resize cannot upscale axis {axis}: {input_size} -> {output_size}"
        )
    if input_size == output_size:
        return np.asarray(values, dtype=np.float64)

    scale = input_size / float(output_size)
    source_lo = np.arange(output_size, dtype=np.float64) * scale
    source_hi = (np.arange(output_size, dtype=np.float64) + 1.0) * scale
    first = np.floor(source_lo).astype(np.int64)
    end = np.ceil(source_hi).astype(np.int64)
    max_cells = int(np.max(end - first))

    output_shape = list(values.shape)
    output_shape[axis] = output_size
    resized = np.zeros(output_shape, dtype=np.float64)
    weight_shape = [1] * values.ndim
    weight_shape[axis] = output_size
    for offset in range(max_cells):
        source_index = first + offset
        valid = source_index < end
        bounded_index = np.clip(source_index, 0, input_size - 1)
        coverage = np.maximum(
            np.minimum(source_hi, source_index + 1.0)
            - np.maximum(source_lo, source_index.astype(np.float64)),
            0.0,
        )
        coverage = np.where(valid, coverage, 0.0).reshape(weight_shape)
        resized += np.take(values, bounded_index, axis=axis) * coverage
    return resized / scale


def area_resize(depth: np.ndarray, output_shape: tuple[int, int]) -> np.ndarray:
    """Resize a 2-D depth field with exact rectangular source-cell overlap weights."""

    if depth.ndim != 2:
        raise CalibrationError(f"Depth arrays must be 2-D, got shape {depth.shape}")
    output_height, output_width = output_shape
    # Shrink width first so the taller intermediate is already narrow.
    resized_width = _axis_area_resize(depth, output_width, axis=1)
    return _axis_area_resize(resized_width, output_height, axis=0)


def _load_depth(path: Path, *, require_nonnegative: bool) -> np.ndarray:
    try:
        depth = np.load(path, allow_pickle=False)
    except (OSError, ValueError) as error:
        raise CalibrationError(f"Cannot load depth prediction {path}: {error}") from error
    if depth.ndim != 2:
        raise CalibrationError(f"Depth prediction must be 2-D: {path} has {depth.shape}")
    if not np.issubdtype(depth.dtype, np.number):
        raise CalibrationError(f"Depth prediction is not numeric: {path} has {depth.dtype}")
    if not np.all(np.isfinite(depth)):
        raise CalibrationError(f"Depth prediction contains NaN or infinity: {path}")
    if require_nonnegative and np.any(depth < 0):
        raise CalibrationError(f"ZipDepth prediction contains negative values: {path}")
    return np.asarray(depth, dtype=np.float64)


def _clip_directories(root: Path) -> dict[str, Path]:
    if not root.is_dir():
        raise CalibrationError(f"Prediction directory does not exist: {root}")
    clips = {entry.name: entry for entry in root.iterdir() if entry.is_dir()}
    if not clips:
        raise CalibrationError(f"Prediction directory contains no clip directories: {root}")
    return clips


def _paired_frames(zip_clip: Path, host_clip: Path) -> list[tuple[Path, Path]]:
    zip_frames = {path.name: path for path in zip_clip.glob("frame_*.npy")}
    host_frames = {path.name: path for path in host_clip.glob("frame_*.npy")}
    if not zip_frames:
        raise CalibrationError(f"Clip contains no frame_*.npy predictions: {zip_clip}")
    if zip_frames.keys() != host_frames.keys():
        missing_host = sorted(zip_frames.keys() - host_frames.keys())
        missing_zip = sorted(host_frames.keys() - zip_frames.keys())
        raise CalibrationError(
            f"Frame pairing mismatch for {zip_clip.name}: "
            f"missing_host={missing_host}, missing_zipdepth={missing_zip}"
        )
    return [(zip_frames[name], host_frames[name]) for name in sorted(zip_frames)]


def _empty_accumulator() -> dict[str, float | int]:
    return {
        "pixels": 0,
        "sum_x": 0.0,
        "sum_y": 0.0,
        "xx": 0.0,
        "xy": 0.0,
        "yy": 0.0,
    }


def _accumulate(
    accumulator: dict[str, float | int], zip_coordinate_numerator: np.ndarray,
    host_coordinate: np.ndarray,
) -> None:
    x = zip_coordinate_numerator
    y = host_coordinate
    accumulator["pixels"] = int(accumulator["pixels"]) + x.size
    accumulator["sum_x"] = float(accumulator["sum_x"]) + float(np.sum(x))
    accumulator["sum_y"] = float(accumulator["sum_y"]) + float(np.sum(y))
    accumulator["xx"] = float(accumulator["xx"]) + float(np.sum(x * x))
    accumulator["xy"] = float(accumulator["xy"]) + float(np.sum(x * y))
    accumulator["yy"] = float(accumulator["yy"]) + float(np.sum(y * y))


def _merge_accumulator(
    destination: dict[str, float | int], source: dict[str, float | int]
) -> None:
    for key in destination:
        destination[key] = destination[key] + source[key]


def _fit_metrics(
    accumulator: dict[str, float | int], gain: float | None = None
) -> dict[str, Any]:
    pixels = int(accumulator["pixels"])
    xx = float(accumulator["xx"])
    xy = float(accumulator["xy"])
    yy = float(accumulator["yy"])
    if pixels <= 0 or xx <= 0.0 or yy <= 0.0:
        raise CalibrationError("Calibration has no non-flat coordinate energy")
    if xy <= 0.0:
        raise CalibrationError(
            "ZipDepth is not high-is-near relative to the host reference: "
            f"coordinate dot product is {xy}"
        )
    fitted_gain = xy / xx if gain is None else gain
    if not math.isfinite(fitted_gain) or fitted_gain <= 0.0:
        raise CalibrationError(f"Invalid fitted ZipDepth coordinate gain: {fitted_gain}")
    scale = 1.0 / fitted_gain
    residual_sum_squares = max(
        yy - 2.0 * fitted_gain * xy + fitted_gain * fitted_gain * xx,
        0.0,
    )
    target_rms = math.sqrt(yy / pixels)
    rmse = math.sqrt(residual_sum_squares / pixels)
    mean_residual = (
        fitted_gain * float(accumulator["sum_x"])
        - float(accumulator["sum_y"])
    ) / pixels
    cosine = xy / math.sqrt(xx * yy)
    return {
        "scale": scale,
        "gain": fitted_gain,
        "high_is_near": True,
        "polarity_cosine": cosine,
        "target_rms": target_rms,
        "residual": {
            "mean": mean_residual,
            "rmse": rmse,
            "relative_rmse": rmse / target_rms,
            "sum_squares": residual_sum_squares,
            "explained_energy": 1.0 - residual_sum_squares / yy,
        },
    }


def calibrate(
    zipdepth_directory: Path,
    host_directory: Path,
    *,
    expected_shapes: Iterable[tuple[int, int]] | None = PRODUCTION_GRAPH_SHAPES,
) -> dict[str, Any]:
    """Calibrate all paired clips and return a JSON-serializable report."""

    zip_clips = _clip_directories(zipdepth_directory)
    host_clips = _clip_directories(host_directory)
    if zip_clips.keys() != host_clips.keys():
        missing_host = sorted(zip_clips.keys() - host_clips.keys())
        missing_zip = sorted(host_clips.keys() - zip_clips.keys())
        raise CalibrationError(
            "Clip pairing mismatch: "
            f"missing_host={missing_host}, missing_zipdepth={missing_zip}"
        )

    groups: dict[tuple[int, int], dict[str, Any]] = {}
    total_frames = 0
    for clip_name in sorted(zip_clips):
        pairs = _paired_frames(zip_clips[clip_name], host_clips[clip_name])
        first_zip = _load_depth(pairs[0][0], require_nonnegative=True)
        first_host = _load_depth(pairs[0][1], require_nonnegative=False)
        zip_shape = tuple(int(value) for value in first_zip.shape)
        first_host_resized = area_resize(first_host, zip_shape)
        host_shape = tuple(int(value) for value in first_host.shape)
        zip_shot_mean = float(np.mean(first_zip, dtype=np.float64))
        host_shot_mean = float(np.mean(first_host_resized, dtype=np.float64))

        clip_accumulator = _empty_accumulator()
        for zip_path, host_path in pairs:
            zip_depth = _load_depth(zip_path, require_nonnegative=True)
            host_depth = _load_depth(host_path, require_nonnegative=False)
            if zip_depth.shape != zip_shape:
                raise CalibrationError(
                    f"ZipDepth shape changed within {clip_name}: "
                    f"{pairs[0][0].name}={zip_shape}, {zip_path.name}={zip_depth.shape}"
                )
            if host_depth.shape != host_shape:
                raise CalibrationError(
                    f"Host shape changed within {clip_name}: "
                    f"{pairs[0][1].name}={host_shape}, {host_path.name}={host_depth.shape}"
                )
            host_resized = area_resize(host_depth, zip_shape)
            zip_centered = zip_depth - zip_shot_mean
            host_coordinate = (host_resized - host_shot_mean) / HOST_V2_RAW_SCALE
            _accumulate(clip_accumulator, zip_centered, host_coordinate)

        group = groups.setdefault(
            zip_shape,
            {"accumulator": _empty_accumulator(), "clips": [], "frames": 0},
        )
        _merge_accumulator(group["accumulator"], clip_accumulator)
        group["clips"].append(
            {
                "name": clip_name,
                "frames": len(pairs),
                "host_shape": [host_shape[0], host_shape[1]],
                "zipdepth_first_frame_mean": zip_shot_mean,
                "host_resized_first_frame_mean": host_shot_mean,
                "accumulator": clip_accumulator,
            }
        )
        group["frames"] += len(pairs)
        total_frames += len(pairs)

    observed_shapes = set(groups)
    expected_shape_set = None if expected_shapes is None else set(expected_shapes)
    if expected_shape_set is not None and observed_shapes != expected_shape_set:
        raise CalibrationError(
            "Unexpected ZipDepth graph shapes: "
            f"expected={sorted(expected_shape_set)}, observed={sorted(observed_shapes)}"
        )

    rendered_groups: dict[str, Any] = {}
    for shape in sorted(groups, key=lambda item: (item[1], item[0])):
        group = groups[shape]
        group_metrics = _fit_metrics(group["accumulator"])
        gain = float(group_metrics["gain"])
        rendered_clips = []
        for clip in group["clips"]:
            # Require every clip, not merely the pooled graph, to agree with high-is-near polarity.
            clip_metrics = _fit_metrics(clip.pop("accumulator"), gain=gain)
            rendered_clips.append({**clip, **clip_metrics})
        height, width = shape
        rendered_groups[f"{width}x{height}"] = {
            "width": width,
            "height": height,
            "frames": int(group["frames"]),
            "pixels": int(group["accumulator"]["pixels"]),
            "clips": rendered_clips,
            **group_metrics,
        }

    return {
        "schema_version": 1,
        "fit": "zip_centered / scale ~= host_centered / 2.25 (through origin)",
        "shot_center": "per-clip first-frame arithmetic mean",
        "host_resize": "exact source-cell area",
        "host_v2_raw_scale": HOST_V2_RAW_SCALE,
        "zipdepth_directory": str(zipdepth_directory),
        "host_directory": str(host_directory),
        "clips": len(zip_clips),
        "frames": total_frames,
        "groups": rendered_groups,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--zipdepth-directory",
        type=Path,
        default=DEFAULT_ZIPDEPTH_DIRECTORY,
        help="root containing ZipDepth clip/frame_*.npy predictions",
    )
    parser.add_argument(
        "--host-directory",
        type=Path,
        default=DEFAULT_HOST_DIRECTORY,
        help="root containing paired host_hybrid_fused clip/frame_*.npy predictions",
    )
    parser.add_argument(
        "--indent", type=int, default=2, help="JSON indentation (default: 2)"
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = calibrate(args.zipdepth_directory, args.host_directory)
    except CalibrationError as error:
        raise SystemExit(f"calibration failed: {error}") from error
    print(json.dumps(report, indent=args.indent, sort_keys=True, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
