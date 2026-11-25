#!/bin/bash
# Prints the biggest methods' sizes (16-bit code units) in the release APK, HomeScreen's class first.
S=${TMPDIR:-/tmp}/viewarr-dex; mkdir -p $S; D=$(ls -d /appdata/claude-code/android/sdk/build-tools/*/ | tail -1)
rm -rf $S/*.dex; python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); [z.extract(n,sys.argv[2]) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')]" ${APK:-/appdata/claude-code/tvapp/app/build/outputs/apk/viewarr/release/app-viewarr-release.apk} $S
for f in $S/classes*.dex; do ${D}dexdump -d $f 2>/dev/null | awk '/Class descriptor/ {cls=$4} /^      name/ {name=$3} /insns size/ {print $4, cls, name}'; done | sort -n | tail -${1:-5}
