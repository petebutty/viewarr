# Baseline Profile

`app/src/main/baseline-prof.txt` lists the code Viewarr uses at start-up and while browsing. AGP packs it into the APK
and `androidx.profileinstaller` has Android compile it ahead of time right after install, so an APK / Downloader
install doesn't run interpreted (on the AM9 that froze the first screen for 0.7–0.8 s twice; pre-compiled: none).

## Refreshing it (now and then, as the app grows)
1. Install a release build on a test box (AM9, rooted) and use it for a few minutes: start it a few times, open
   Movies, Shows, the guide, a title page, the player.
2. `adb shell cmd package dump-profiles io.github.petebutty.viewarr`
3. `adb shell su -c 'cat /data/misc/profman/io.github.petebutty.viewarr-primary.prof.txt' > prof.txt`
4. `python3 tools/baseline_profile.py prof.txt app/build/outputs/mapping/release/mapping.txt` (the mapping.txt of
   the SAME build that was installed) → rewrites `app/src/main/baseline-prof.txt`.
