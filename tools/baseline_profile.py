"""Rebuild app/src/main/baseline-prof.txt from a device profile dump (see tools/baseline-profile.md)."""
import collections, re, sys

prof, mapping = sys.argv[1], sys.argv[2]
back = {}
for line in open(mapping, encoding="utf-8"):
    m = None if line.startswith(("#", " ")) else re.match(r"(\S+) -> (\S+):", line)
    if m:
        back[m.group(2)] = m.group(1)
used, startup, section = collections.Counter(), set(), None
cls_re = re.compile(r"^\s*(?:[\w$.\[\]<>]+\s+)?([\w$.]+)\.([\w$<>]+)\(")
for raw in open(prof, encoding="utf-8").read().splitlines():
    s = raw.strip()
    if s.endswith("methods:") or s == "classes:":
        section = s
        continue
    if not s or section is None:
        continue
    if section == "classes:":
        for c in filter(None, (x.strip() for x in s.split(","))):
            used[c] += 0
        continue
    for item in s.split("], "):
        m = cls_re.match(item.strip())
        if m:
            used[m.group(1)] += 1
            if section == "startup methods:":
                startup.add(m.group(1))
out = []
for obf in sorted(used):
    orig = back.get(obf, obf)
    if not re.match(r"^[A-Za-z_$][\w$]*(\.[\w$]+)+$", orig):
        continue
    desc = "L" + orig.replace(".", "/") + ";"
    if used[obf] > 0:
        out.append(("HSPL" if obf in startup else "HPL") + desc + "->**(**)**")
    out.append(desc)
with open("app/src/main/baseline-prof.txt", "w") as f:
    f.write("# Viewarr Baseline Profile (tools/baseline-profile.md): start-up and browsing code, compiled ahead of time on install.\n")
    f.write("\n".join(out) + "\n")
print(len(used), "classes,", len(out), "rules")
