#!/system/bin/sh
# CDSPAdvanced v3.3 deploy — finds APK anywhere, extracts zips, installs

APP_PACKAGE="com.cdspadvanced"
APP_ACTIVITY="com.cdspadvanced.MainActivity"
APP_BIN_DIR="/data/data/$APP_PACKAGE/files/bin"
HAS_ROOT=false

echo ""
echo "=============================================="
echo "  CDSPAdvanced Deploy"
echo "=============================================="

# Root check
if command -v su > /dev/null 2>&1; then
    T=$(su -c "echo ok" 2>/dev/null)
    [ "$T" = "ok" ] && HAS_ROOT=true && echo "  Root: yes" || echo "  Root: no (su broken)"
else
    echo "  Root: no"
fi

run_root() {
    if [ "$HAS_ROOT" = "true" ]; then su -c "$*"; else eval "$@"; fi
}

# ── Step 1: Extract any CDSPAdvanced zip ──
echo ""
echo "[1/5] Checking for zips..."
for d in /sdcard/Download /sdcard/Downloads /storage/emulated/0/Download /storage/emulated/0/Downloads /sdcard; do
    for z in "$d"/CDSPAdvanced*.zip "$d"/cdsp*.zip; do
        if [ -f "$z" ]; then
            echo "  Extracting: $z"
            if command -v unzip > /dev/null 2>&1; then
                unzip -o "$z" -d "$d" 2>/dev/null
                echo "  Done"
            else
                echo "  No unzip! Run: pkg install unzip"
            fi
            break 2
        fi
    done
done

# ── Step 2: Find the APK ──
echo ""
echo "[2/5] Finding APK..."
APK=""
for d in /sdcard/Download /sdcard/Downloads /storage/emulated/0/Download /storage/emulated/0/Downloads /sdcard /data/local/tmp; do
    for f in "$d"/CDSPAdvanced*.apk "$d"/cdspadvanced*.apk "$d"/app-release*.apk; do
        if [ -f "$f" ]; then
            APK="$f"
            echo "  Found: $APK"
            break 2
        fi
    done
done

if [ -z "$APK" ] && command -v find > /dev/null 2>&1; then
    echo "  Deep search..."
    APK=$(find /sdcard -maxdepth 3 -iname "*cdsp*.apk" -type f 2>/dev/null | head -1)
    [ -n "$APK" ] && echo "  Found: $APK"
fi

# Check if already installed
INSTALLED=false
pm list packages 2>/dev/null | grep -q "$APP_PACKAGE" && INSTALLED=true

if [ -z "$APK" ]; then
    if [ "$INSTALLED" = "true" ]; then
        echo "  No APK found but app already installed"
    else
        echo "  ERROR: No APK found. Put it in /sdcard/Download/"
        exit 1
    fi
fi

# ── Step 3: Install ──
echo ""
echo "[3/5] Installing..."
if [ -n "$APK" ]; then
    # Uninstall old version first (different signing keys cause INSTALL_FAILED_UPDATE_INCOMPATIBLE)
    if [ "$INSTALLED" = "true" ]; then
        echo "  Removing old version..."
        run_root "pm uninstall $APP_PACKAGE" 2>/dev/null
        sleep 1
    fi

    if [ "$HAS_ROOT" = "true" ]; then
        OUT=$(su -c "pm install -r -g '$APK'" 2>&1)
    else
        OUT=$(pm install -r "$APK" 2>&1)
    fi
    echo "  $OUT"
else
    echo "  Skipped (already installed)"
fi

# Verify
if pm list packages 2>/dev/null | grep -q "$APP_PACKAGE"; then
    echo "  OK: $APP_PACKAGE installed"
else
    echo "  FAILED. Try manually: open file manager, tap the APK"
    exit 1
fi

# ── Step 4: Permissions + minidspd ──
echo ""
echo "[4/5] Permissions & daemon..."
run_root "pm grant $APP_PACKAGE android.permission.RECORD_AUDIO" 2>/dev/null && echo "  RECORD_AUDIO: ok" || echo "  RECORD_AUDIO: grant manually"

# Find minidspd
BIN=""
for p in \
    /sdcard/Download/minidspd \
    /sdcard/Downloads/minidspd \
    /storage/emulated/0/Download/minidspd \
    /data/data/com.termux/files/home/minidspd \
    /data/data/com.termux/files/usr/bin/minidspd \
    /data/local/tmp/minidspd \
    /data/local/tmp/minidsp/minidspd \
; do
    if [ -f "$p" ] 2>/dev/null; then BIN="$p"; break; fi
done

# Root-only paths
if [ -z "$BIN" ] && [ "$HAS_ROOT" = "true" ]; then
    for p in /data/data/com.termux/files/home/minidspd /data/data/com.termux/files/usr/bin/minidspd; do
        X=$(su -c "test -f '$p' && echo y" 2>/dev/null)
        [ "$X" = "y" ] && BIN="$p" && break
    done
fi

if [ -n "$BIN" ]; then
    echo "  minidspd: $BIN"
    run_root "mkdir -p '$APP_BIN_DIR'"
    run_root "cp '$BIN' '$APP_BIN_DIR/minidspd'"
    run_root "chmod 755 '$APP_BIN_DIR/minidspd'"
    if [ "$HAS_ROOT" = "true" ]; then
        UID=$(su -c "ls -ld '/data/data/$APP_PACKAGE'" 2>/dev/null | awk '{print $3}')
        [ -n "$UID" ] && su -c "chown '$UID:$UID' '$APP_BIN_DIR' '$APP_BIN_DIR/minidspd'"
    fi
    echo "  Installed to app dir"
else
    echo "  minidspd not found — start it from Termux for now"
fi

run_root "killall minidspd" 2>/dev/null || true

# ── Step 5: Launch ──
echo ""
echo "[5/5] Launching..."
run_root "am force-stop '$APP_PACKAGE'" 2>/dev/null
sleep 1
am start -n "$APP_PACKAGE/$APP_ACTIVITY" 2>&1

echo ""
echo "=============================================="
echo "  Done. Tap CDSPAdvanced to launch anytime."
echo "=============================================="
echo ""
