# Baritone 1.21.11 development handoff

This repository is a staging area for verified investigation notes and patch artifacts for the Baritone project. It is **not** a complete clone of the upstream project.

## Upstream project

- Repository: https://github.com/Nevarielle/baritone-1.21.11
- Inspected branch: `update/1.21.11-fabric`
- Target file: `src/main/java/baritone/utils/schematic/format/defaults/LitematicaSchematic.java`
- Inspected source blob SHA: `0b40cd5700eb65fc8507995e7eaf64785cedc192`

## Current confirmed finding

The inspected `getVolume()` implementation multiplied three `int` dimensions before returning the result as a `long`. That can overflow before widening.

The proposed patch uses checked multiplication, rejects the `Integer.MIN_VALUE` dimension edge case, rejects volumes beyond the current int-indexed decode loop, and reuses the dimension helper when allocating the three-dimensional state array.

## Verification status

- Source was inspected through the GitHub integration.
- Proposed patch is saved under `patches/0001-fix-litematica-volume-overflow.patch`.
- **Not yet applied to a complete local checkout.**
- **Not compiled or test-verified.**
- The Muse ZIP/patch links supplied in the conversation failed to download from the current execution environment. This repository therefore does not currently contain the complete Baritone project source.
- BuilderProcess placement reliability and the long-term calculator construction/functional verification remain unfinished.

## Apply and verify

From the root of a fresh clone of the upstream repository:

```bash
git switch update/1.21.11-fabric
git switch -c fix/litematica-volume-overflow
git apply --check /path/to/patches/0001-fix-litematica-volume-overflow.patch
git apply /path/to/patches/0001-fix-litematica-volume-overflow.patch
```

Use JDK 21, then run:

Windows:
```bat
gradlew.bat :fabric:remapJar
```

Linux/macOS:
```bash
./gradlew :fabric:remapJar
```

Run the appropriate tests and record their actual results. Do not treat this patch as verified until it applies cleanly and the code is built and tested.
