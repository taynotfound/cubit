#!/usr/bin/env bash
# Records the debug build playing itself on the emulator, for promo reels (tools/reels.py cuts them).
# Needs a booted emulator with the debug APK installed (./gradlew installGithubDebug). Writes raw_<name>.mp4,
# start_<name>.txt and log_<name>.txt (tap and win times) into $1.
set -euo pipefail
OUT=${1:?output folder}; mkdir -p "$OUT"
A=${ADB:-~/Android/Sdk/platform-tools/adb}
# A tidy status bar: fixed clock, full battery, no notification icons.
$A shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 0900" "battery -e level 100 -e plugged false" "network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none" "notifications -e visible false"; do
  set -- $c; cmd=$1; shift; $A shell am broadcast -a com.android.systemui.demo -e command "$cmd" "$@" >/dev/null
done
rec() { # name seconds am-extras…
  local name=$1 secs=$2; shift 2
  $A logcat -c
  $A shell am start -S -W -n de.taymaerz.cubit/com.cubit.game.MainActivity "$@" >/dev/null
  # 720×1600 keeps the software-rendered emulator near 30 fps.
  $A shell "date +%s.%N > /sdcard/start.txt; screenrecord --size 720x1600 --bit-rate 14000000 --time-limit $secs /sdcard/r.mp4"
  $A pull /sdcard/r.mp4 "$OUT/raw_$name.mp4" >/dev/null
  $A shell cat /sdcard/start.txt > "$OUT/start_$name.txt"
  $A logcat -d -v epoch -s CubitDemo:I > "$OUT/log_$name.txt"
}
rec level 30 --es demo level:14 --es mode NORMAL --ei taps 24 --ef pace 1.3
rec clean 30 --es demo level:12 --es mode CLEAN --ei taps 26 --ef pace 1.2
rec bombs 24 --es demo free:14:5:771 --es mode ZEN --ei taps 60 --ef pace 1.4
rec endless 50 --es demo endless --es mode EASY --ei taps 6 --ef pace 1.5
