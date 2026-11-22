#!/usr/bin/env python3
"""
Builds app/src/main/assets/changelog.md from the GitHub release notes, newest first, for the in-app changelog
(Settings -> About -> Changelog). Run before each release build, after the new release's notes are written:

    python3 tools/changelog.py                      # all published releases
    python3 tools/changelog.py --next 0.28.4 notes.md   # also put the coming release (not published yet) on top

Needs `gh` signed in. The app reads a small Markdown subset: "## <version> · <date>" starts a version,
"### " a section, "- " / "  - " bullets, **bold** and `code`; links keep only their text.
"""
import json, re, subprocess, sys, datetime, pathlib

REPO = "petebutty/viewarr"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/changelog.md"


def tidy(body: str) -> str:
    lines = []
    for raw in body.replace("\r\n", "\n").split("\n"):
        line = raw.rstrip()
        # The release's own title ("## Viewarr 0.28.2"): the app shows the version itself.
        if re.match(r"^#{1,2}\s", line):
            continue
        # Images and HTML don't belong on a TV screen; links keep their text.
        if re.match(r"^\s*(!\[|<)", line):
            continue
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
        line = re.sub(r"^\*\s", "- ", line)
        line = re.sub(r"^(\s+)\*\s", r"\1- ", line)
        lines.append(line)
    text = "\n".join(lines).strip()
    return re.sub(r"\n{3,}", "\n\n", text)


def releases():
    out = subprocess.run(["gh", "api", f"repos/{REPO}/releases?per_page=100", "--paginate"],
                         check=True, capture_output=True, text=True).stdout
    # --paginate concatenates JSON arrays.
    items = json.loads("[" + out.replace("][", ",").strip()[1:-1] + "]") if out.strip() else []
    return [r for r in items if not r.get("draft")]


def write_whats_new(notes: str):
    """The once-per-update card (assets/whats_new.txt) from the release's own notes, so it never shows an old version's
    text again (2026-11-22: 0.29.7 showed 0.29.0's). Heading: the notes' first sentence line; points: its sections
    ("### …"), else its first bullets, plain text, at most four."""
    plain = lambda t: re.sub(r"\*\*|__|`|\[([^\]]*)\]\([^)]*\)", lambda m: m.group(1) or "", t).strip()
    lines = [l.strip() for l in notes.splitlines()]
    heading = next((plain(l) for l in lines if l and not l.startswith(("#", "-", "*", ">"))), "What's new")
    points = [plain(l.lstrip("#").strip()) for l in lines if l.startswith("### ")]
    points = [p for p in points if p.lower() not in ("fixes", "fix")]
    if not points:
        points = [plain(l[2:]).split(":")[0] for l in lines if l.startswith(("- ", "* "))]
    body = ["# Shown once, the first time a new version opens (not on a fresh install). Written by tools/changelog.py",
            "# from the release notes. Lines starting with \"- \" are the points; the first other line is the heading.",
            heading] + ["- " + p for p in points[:4]]
    (OUT.parent / "whats_new.txt").write_text("\n".join(body) + "\n")


def main():
    entries = []
    args = sys.argv[1:]
    if args[:1] == ["--next"]:
        version, notes = args[1], pathlib.Path(args[2]).read_text()
        entries.append((version, datetime.date.today(), tidy(notes)))
    for r in releases():
        version = r["tag_name"].lstrip("v")
        if entries and version == entries[0][0]:
            continue
        date = datetime.date.fromisoformat(r["published_at"][:10])
        entries.append((version, date, tidy(r.get("body") or "")))

    def key(e):
        return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", e[0]))
    entries.sort(key=key, reverse=True)

    parts = ["# Viewarr changelog (built by tools/changelog.py from the GitHub releases; don't edit by hand)\n"]
    for version, date, text in entries:
        parts.append(f"## {version} · {date.strftime('%-d %b %Y')}\n\n{text or 'Fixes and small improvements.'}\n")
    OUT.write_text("\n".join(parts))
    if args[:1] == ["--next"]:
        write_whats_new(entries[0][2] if entries and entries[0][0] == args[1] else tidy(notes))
    print(f"wrote {OUT} ({len(entries)} versions, {OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
