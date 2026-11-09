"""Require the current public JVM API to still contain everything a published release had.

`dumpApi` rewrites `api/<module>.txt` from the build on every host run, so on its own nothing fails when
a public member disappears: the dump simply follows the code. `api/legacy/<version>/<module>.txt` holds
the same `javap -public` dump taken from the artifacts published on Maven Central; the host gate requires
the current dump to be a superset of it (every public class, member and supertype still present).

    legacy_abi.py generate --version 1.1.0 --artifacts-dir <Maven layout root>
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# Every published module that has a public API dump: the three consumer-pinned ones plus the optional resources loader.
MODULES = ('android-runtime', 'android-window', 'android-lifecycle', 'android-resources-loader')

GROUP_PATH = "io/github/jichuo1/meguviz"
HEADER = re.compile(r"^(?P<modifiers>(?:[\w-]+ )*?)(?P<kind>@interface|interface|class|enum) (?P<name>[^\s<]+)")


def split_top_level(text: str, separator: str) -> list[str]:
    parts, depth, current = [], 0, []
    for character in text:
        if character == "<":
            depth += 1
        elif character == ">":
            depth -= 1
        if character == separator and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    parts.append("".join(current).strip())
    return [part for part in parts if part]


def parse_header(line: str) -> dict:
    text = line.strip()
    if text.endswith("{"):
        text = text[:-1].strip()
    declaration, supertypes = text, []
    depth = 0
    for index in range(len(text)):
        if text[index] == "<":
            depth += 1
        elif text[index] == ">":
            depth -= 1
        elif depth == 0 and (text.startswith(" extends ", index) or text.startswith(" implements ", index)):
            declaration = text[:index]
            rest = text[index:]
            supertypes = [item for keyword in (" extends ", " implements ")
                          for segment in re.findall(rf"{keyword}(.*?)(?= (?:extends|implements) |$)", rest)
                          for item in split_top_level(segment, ",")]
            break
    match = HEADER.match(declaration)
    if not match:
        raise ValueError(f"Unrecognised class header: {line!r}")
    modifiers = set(match.group("modifiers").split())
    return {"kind": match.group("kind"), "name": match.group("name"), "modifiers": frozenset(modifiers),
            "shape": frozenset(modifiers & {"final", "abstract"}), "supertypes": frozenset(supertypes)}


def parse_dump(text: str) -> dict[str, dict]:
    """Class name -> {header, members}; a member is its declaration plus its descriptor lines."""
    # javap prints blank lines between members, so sections are found by indentation, not by gaps:
    # a class header starts in column 0, a member at two spaces, its descriptor at four.
    classes: dict[str, dict] = {}
    header, members, current = None, set(), []

    def close_member() -> None:
        nonlocal current
        if current:
            members.add("\n".join(current))
        current = []

    def close_class() -> None:
        close_member()
        if header is not None:
            classes[header["name"]] = {"header": header, "members": set(members)}
        members.clear()

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.startswith("//") or line.startswith("Compiled from") or line == "}":
            continue
        if not line.startswith(" "):
            close_class()
            header = parse_header(line)
        elif re.match(r"^ {2}\S", line):
            close_member()
            current = [line.strip()]
        elif current:
            current.append(line.strip())
    close_class()
    return classes


def read_allowed(path: Path) -> set[str]:
    """Reviewed, intentional removals: `<class> :: <member declaration>` per line, `#` comments."""
    if not path.is_file():
        return set()
    allowed = set()
    for line in path.read_text("utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            allowed.add(re.sub(r"\s+", " ", line))
    return allowed


def compare(legacy: dict[str, dict], current: dict[str, dict], allowed: set[str]) -> list[str]:
    problems = []
    for name, old in sorted(legacy.items()):
        if f"{name} :: *" in allowed:
            continue
        new = current.get(name)
        if new is None:
            problems.append(f"class removed: {name}")
            continue
        before, after = old["header"], new["header"]
        if before["kind"] != after["kind"]:
            problems.append(f"{name}: kind changed {before['kind']} -> {after['kind']}")
        if before["shape"] != after["shape"]:
            problems.append(f"{name}: modifiers changed {sorted(before['shape'])} -> {sorted(after['shape'])}")
        if "public" in before["modifiers"] and "public" not in after["modifiers"]:
            problems.append(f"{name}: no longer public")
        for supertype in sorted(before["supertypes"] - after["supertypes"]):
            problems.append(f"{name}: no longer extends or implements {supertype}")
        for member in sorted(old["members"] - new["members"]):
            declaration = re.sub(r"\s+", " ", member.split("\n", 1)[0])
            if f"{name} :: {declaration}" in allowed:
                continue
            descriptor = member.split("\n", 1)[1].strip() if "\n" in member else ""
            problems.append(f"{name}: member missing or changed: {declaration} {descriptor}".rstrip())
    return problems


def check_dump(module: str, current_text: str, root: Path = ROOT) -> list[str]:
    """Problems between every committed published-release baseline of `module` and `current_text`."""
    releases = sorted(path for path in (root / 'api/legacy').glob('*') if path.is_dir())
    if not releases:
        raise ValueError('api/legacy has no published-release baseline; run scripts/legacy_abi.py generate')
    current = parse_dump(current_text)
    problems = []
    for release in releases:
        baseline = release / f'{module}.txt'
        if not baseline.is_file():
            raise ValueError(f'Missing published-release API baseline: api/legacy/{release.name}/{module}.txt')
        allowed = read_allowed(release / 'allowed-removals.txt')
        problems.extend(f'{release.name}/{module}: {item}' for item in compare(parse_dump(baseline.read_text('utf-8')), current, allowed))
    return problems


def generate(version: str, artifacts: Path, root: Path = ROOT) -> int:
    javap = shutil.which('javap')
    if not javap and os.environ.get('JAVA_HOME'):
        javap = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap'))
    if not javap or not Path(javap).is_file():
        raise RuntimeError('javap is required (put it on PATH or set JAVA_HOME)')
    work = root / 'build/legacy-abi'
    target = root / 'api/legacy' / version
    target.mkdir(parents=True, exist_ok=True)
    for module in MODULES:
        artifact = f'meguviz-{module}'
        aar = artifacts / GROUP_PATH / artifact / version / f'{artifact}-{version}.aar'
        if not aar.is_file():
            raise RuntimeError(f'Published artifact not found: {aar}')
        extracted = work / module
        extracted.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(aar) as archive:
            (extracted / 'classes.jar').write_bytes(archive.read('classes.jar'))
        with zipfile.ZipFile(extracted / 'classes.jar') as archive:
            classes = sorted(name[:-6].replace('/', '.') for name in archive.namelist()
                             if name.endswith('.class') and not name.rsplit('/', 1)[-1].startswith('R$') and name.rsplit('/', 1)[-1] != 'R.class')
        result = subprocess.run([javap, '-public', '-classpath', str(extracted / 'classes.jar'), *classes],
                                capture_output=True, text=True, check=True)
        sections = [section.strip() for section in re.split(r'Compiled from "[^"]+"\r?\n', result.stdout)
                    if section.lstrip().startswith('public ')]
        digest = hashlib.sha256(aar.read_bytes()).hexdigest()
        text = (f'// javap -public of the published release {version} (Maven Central, io.github.jichuo1.meguviz).\n'
                f'// Generated by scripts/legacy_abi.py generate; do not edit by hand.\n'
                f'// source {aar.name} sha256={digest}\n\n' + '\n\n'.join(sorted(sections)) + '\n')
        (target / f'{module}.txt').write_bytes(text.encode('utf-8'))
        print(f'wrote api/legacy/{version}/{module}.txt ({len(sections)} public classes)')
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('mode', choices=('generate',))
    parser.add_argument('--version', required=True)
    parser.add_argument('--artifacts-dir', type=Path, required=True)
    args = parser.parse_args()
    return generate(args.version, args.artifacts_dir.resolve(strict=True))


if __name__ == '__main__':
    sys.exit(main())
