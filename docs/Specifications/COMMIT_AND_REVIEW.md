# Commit Conventions
## BLE Reference Library — Git Flow

---

## Branching Model (Git Flow)

```
main          ← stable, tagged releases only
  └── develop ← integration branch, always green
        └── feature/phase<NN>-sprint<NN>-<slug>   ← one branch per sprint
        └── fix/phase<NN>-sprint<NN>-<slug>        ← bug fixes on completed sprints
        └── docs/<slug>                             ← documentation-only changes
```

### Rules

- **Never commit directly to `main` or `develop`.**
- Feature branches branch from `develop` and merge back to `develop` via PR.
- `main` receives merges from `develop` only at phase completion (after all phase sprints are `done`).
- Each sprint = one feature branch. Do not combine multiple sprints in one branch.
- Branch must be deleted after merge.

---

## Branch Naming

```
feature/phase<NN>-sprint<NN>-<slug>
fix/phase<NN>-sprint<NN>-<slug>
docs/<slug>
```

| Token | Format | Example |
|-------|--------|---------|
| `<NN>` | Zero-padded 2 digits | `01`, `10` |
| `<slug>` | kebab-case, ≤ 30 chars | `passive-scan`, `muse-eeg-parser` |

Examples:
```
feature/phase01-sprint01-passive-scan
feature/phase02-sprint27-muse
feature/phase04-sprint63-muse-editor
fix/phase01-sprint10-muse-bit-packing
docs/readme-python
```

---

## Commit Message Format

```
<type>(<scope>): <short description>

[optional body]

[optional footer]
```

### Types

| Type | When to use |
|------|------------|
| `feat` | New function, class, or file |
| `test` | Adding or fixing tests only |
| `fix` | Bug fix in existing code |
| `docs` | README, docstring, comment changes only |
| `refactor` | Code restructured, no behavior change |
| `chore` | Build config, dependencies, CI |

### Scopes by platform

| Scope | Platform |
|-------|---------|
| `ble-py` | Python / Bleak |
| `ble-swift` | Swift / CoreBluetooth |
| `ble-js` | JavaScript Node.js |
| `ble-web` | JavaScript Web Bluetooth |
| `ble-unity` | Unity C# (any target) |
| `ble-quest` | Unity Android / Meta Quest specifics |
| `ble-cpp` | C++ / SimpleBLE |
| `ble-shared` | `00_shared/` content |

### Short description rules

- Imperative mood: "add scan filter" not "added scan filter"
- No period at end
- Max 72 characters
- Enough context to understand without reading the diff

### Examples

```
feat(ble-py): passive BLE scan with configurable duration

feat(ble-py): Muse S EEG parser decoupled from neuropalite

test(ble-py): unit tests for EEG packet parser with hardcoded byte vectors

feat(ble-swift): uint:12 EEG parser cross-validated against Python reference

fix(ble-unity): _intentionalDisconnect flag not reset on reconnect

docs(ble-cpp): document BlueZ MTU negotiation advantage over WinRT

chore: add SimpleBLE 0.7 to CMakeLists.txt
```

### Multi-commit sprint workflow

A sprint typically produces 2–3 commits:

```bash
# 1. Implementation
git commit -m "feat(ble-py): BLEConnectionManager with connect/disconnect/retry"

# 2. Tests
git commit -m "test(ble-py): connection lifecycle unit tests with mocked BleakClient"

# 3. Documentation (if separate)
git commit -m "docs(ble-py): document _intentionalDisconnect pattern in module header"
```

---

## Pull Request Rules

### Title format
```
[Phase<NN>][Sprint<NN>] <Short description>
```
Example: `[Phase01][Sprint10] Muse S EEG standalone parser`

### PR body — required sections

```markdown
## Sprint
Sprint XX — <name>

## Changes
- <bullet per file added or modified>

## Tests
- <N> unit tests added
- All passing: yes / no

## Acceptance criteria
- [ ] criterion 1
- [ ] criterion 2

## Cross-validation (if Muse parser)
- [ ] Output matches Python reference for all test vectors in muse_test_vectors.json

## Notes
<anything a reviewer needs to know>
```

### Merge rules

- **Squash and merge** — one commit per sprint on `develop`
- Squash commit message = PR title in commit format:
  ```
  feat(ble-py): Muse S EEG standalone parser (Sprint 10)
  ```
- Delete branch after merge
- Update `SPRINT_TRACKER.yaml`: set `status: done`, fill `completed_at`

### Phase completion merge (develop → main)

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase <NN> complete — <platform name>"
git tag phase<NN>-complete
git push origin main --tags
```

No squash on phase merges — preserve the sprint-level history on `main`.

---

## SPRINT_TRACKER.yaml Update Protocol

Before opening a PR:
```yaml
status: in_review
```

After merge:
```yaml
status: done
completed_at: "YYYY-MM-DD"
```

If blocked:
```yaml
status: blocked
notes: "Waiting for sprint 10 to complete (Muse test vectors needed)"
```

---
---

# Code Review Checklist
## BLE Reference Library

> Use this checklist for every PR before approving.
> Items marked **[BLOCKING]** must be resolved before merge.
> Items marked **[RECOMMENDED]** are strong suggestions.

---

## Structure & Header

- [ ] **[BLOCKING]** Mandatory header block present (see CODING_STANDARDS §1.1)
- [ ] **[BLOCKING]** Header `Module`, `Platform`, `Sprint`, `Phase`, `Purpose`, `Usage`, `Ref` fields all filled
- [ ] **[BLOCKING]** No imports from other library modules (standalone rule)
- [ ] **[RECOMMENDED]** Section order correct: imports → constants → data structures → functions → entrypoint

## Naming & Style

- [ ] **[BLOCKING]** Naming conventions followed for the target language (see CODING_STANDARDS §2)
- [ ] **[BLOCKING]** No magic numbers except 0, 1, -1, powers of 2 in bit ops
- [ ] **[RECOMMENDED]** No function exceeds 50 lines
- [ ] **[RECOMMENDED]** `_intentional_disconnect` (or equivalent) flag present in connection modules

## Documentation

- [ ] **[BLOCKING]** Every public function / method has a docstring in the correct format for the language
- [ ] **[BLOCKING]** Any function with platform-specific behavior documents it explicitly (see CODING_STANDARDS §5.3)
- [ ] **[BLOCKING]** Muse parser files include cross-validation comment referencing `muse_test_vectors.json`
- [ ] **[RECOMMENDED]** Constants with non-obvious values have an inline comment explaining units or source

## Tests

- [ ] **[BLOCKING]** Test file exists and follows naming convention (see CODING_STANDARDS §4.1)
- [ ] **[BLOCKING]** All BLE hardware mocked — no real device dependency in unit tests
- [ ] **[BLOCKING]** Parser tests use hardcoded byte sequences with expected values in comments
- [ ] **[BLOCKING]** All acceptance criteria from SPRINT_TRACKER.yaml checked off
- [ ] **[RECOMMENDED]** Coverage: 100% line coverage on pure functions (parsers, math, formatters)
- [ ] **[RECOMMENDED]** At least one error-path test per async function

## Muse EEG (if applicable)

- [ ] **[BLOCKING]** `MUSE_EEG_SCALE_FACTOR = 0.48828125` used (not inlined)
- [ ] **[BLOCKING]** `MUSE_12BIT_MIDPOINT = 2048` used (not inlined as magic number)
- [ ] **[BLOCKING]** Packet counter (first uint16) discarded, not used as a sample
- [ ] **[BLOCKING]** Test vectors from `00_shared/muse_test_vectors.json` used
- [ ] **[BLOCKING]** Output values match Python reference `01_python_bleak/10_muse_example.py` for all test vectors

## Git

- [ ] **[BLOCKING]** Branch name follows `feature/phase<NN>-sprint<NN>-<slug>` format
- [ ] **[BLOCKING]** Each commit follows `<type>(<scope>): <description>` format
- [ ] **[BLOCKING]** PR targets `develop`, not `main`
- [ ] **[RECOMMENDED]** No more than 3 commits on the branch (feat + test + docs)
- [ ] **[RECOMMENDED]** PR body includes all required sections

## Cross-platform Consistency

- [ ] **[RECOMMENDED]** If implementing the same module as another platform, API shape is consistent with that platform's version
- [ ] **[RECOMMENDED]** Distance estimation formula matches `ble_utils.estimate_distance()` in Python / C++
- [ ] **[RECOMMENDED]** Error handling follows the same pattern as the Python reference (error hierarchy, retry decorator)

---

## Quick Reference — Blocking Items Summary

A PR must not be merged if any of these are unresolved:

1. Header block missing or incomplete
2. Imports from other library modules
3. Missing docstrings on public functions
4. Platform behavior not documented
5. Real BLE hardware required in unit tests
6. Parser tests without hardcoded byte sequences
7. Muse parser: wrong scale factor, midpoint, or counter not discarded
8. Muse parser: not cross-validated against Python reference
9. Branch targets `main` instead of `develop`
10. Any acceptance criterion from `SPRINT_TRACKER.yaml` unchecked
