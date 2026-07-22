"""Shared filesystem guards for loose Client SBS model generators.

Production archives are published separately.  Intermediate complete models must stay in the
client repository's ignored build/temp trees, never in Android source assets or Apollo-3D.
"""

from __future__ import annotations

from pathlib import Path


class LooseModelPathError(RuntimeError):
    """Raised when a model generator would read or write through a forbidden path."""


def _has_component(path: Path, component: str) -> bool:
    return any(part.casefold() == component.casefold() for part in path.parts)


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _is_android_source_asset(path: Path, repository_root: Path) -> bool:
    android_sources = (repository_root / "app" / "src").resolve()
    if not _is_relative_to(path, android_sources):
        return False
    relative_parts = path.relative_to(android_sources).parts
    return any(part.casefold() == "assets" for part in relative_parts)


def validate_loose_model_paths(
    source: Path,
    output: Path,
    repository_root: Path,
) -> tuple[Path, Path]:
    """Resolve and validate one loose-model source/output pair.

    ``output`` may be either the generated model file or a directory that will contain generated
    models.  Only the client checkout's ``build`` and ``temp`` trees are valid destinations.  The
    source can live elsewhere, but neither path may traverse Apollo-3D or Android source assets.
    """
    unresolved_source = Path(source)
    unresolved_output = Path(output)
    resolved_repository = Path(repository_root).resolve()
    resolved_source = unresolved_source.resolve()
    resolved_output = unresolved_output.resolve()

    for label, unresolved, resolved in (
        ("source", unresolved_source, resolved_source),
        ("output", unresolved_output, resolved_output),
    ):
        if _has_component(unresolved, "apollo-3d") or _has_component(
            resolved, "apollo-3d"
        ):
            raise LooseModelPathError(
                f"Client SBS loose-model {label} must never use the Apollo-3D tree"
            )
        if _is_android_source_asset(resolved, resolved_repository):
            raise LooseModelPathError(
                f"Client SBS loose-model {label} must not use Android source assets"
            )

    if resolved_source == resolved_output:
        raise LooseModelPathError("Client SBS loose-model output must differ from its source")

    allowed_output_roots = (
        (resolved_repository / "build").resolve(),
        (resolved_repository / "temp").resolve(),
    )
    if not any(
        _is_relative_to(resolved_output, allowed_root)
        for allowed_root in allowed_output_roots
    ):
        raise LooseModelPathError(
            "Client SBS loose-model output must stay under the client build/ or temp/ tree"
        )

    return resolved_source, resolved_output
