#!/usr/bin/env bash
# 実機（または指定端末）の上で debug 版（dev.kirin.kiwa.debug）だけを対象に
# 「開く → 書く → 保存 → 止めて起こし直す → 開き直す」と W1・W2 の判定を流し、機械で判定する。
#
#   tools/device-test.sh --serial <serial> --allow-device [--out <dir>] [--no-build] [--apk <path>] [--skip-install]
#
# 前提・安全条件・判定の中身・弱い所は、開発の記録（docs/handoff.md。公開の repo には置いていない）。端末が要るので tools/check.sh には入れない。
# 終了コード: 0 全部通った / 1 判定または復元が外れた / 2 事前条件・安全条件を満たさず止まった
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

SERIAL=""
ALLOW_DEVICE=0
OUT=""
BUILD=1
APK_ARG=""
SKIP_INSTALL=0

usage() {
  echo "使い方: $0 --serial <serial> --allow-device [--out <dir>] [--no-build] [--apk <path>] [--skip-install]" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      if [[ $# -lt 2 || -z "${2:-}" || "${2:0:2}" == "--" ]]; then
        echo "NG: --serial の値が無い" >&2
        usage
        exit 2
      fi
      SERIAL="$2"
      shift 2
      ;;
    --allow-device)
      ALLOW_DEVICE=1
      shift
      ;;
    --out)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "NG: --out の値が無い" >&2
        usage
        exit 2
      fi
      OUT="$2"
      shift 2
      ;;
    --no-build)
      BUILD=0
      shift
      ;;
    --apk)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "NG: --apk の値が無い" >&2
        usage
        exit 2
      fi
      APK_ARG="$2"
      shift 2
      ;;
    --skip-install)
      SKIP_INSTALL=1
      shift
      ;;
    *)
      echo "NG: 未知の引数: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# ---- 1. 必須引数の確認（adb を一度も呼ばない） ----
if [[ -z "$SERIAL" || $ALLOW_DEVICE -ne 1 ]]; then
  missing=()
  [[ -z "$SERIAL" ]] && missing+=("--serial")
  [[ $ALLOW_DEVICE -ne 1 ]] && missing+=("--allow-device")
  echo "NG: 必須引数が足りない（${missing[*]}）。端末に触れず止める" >&2
  usage
  exit 2
fi

[[ -n "$OUT" ]] || OUT="$(mktemp -d)"
mkdir -p "$OUT"

# 対象は debug 版に固定。release 版は一切操作しない。
PKG="dev.kirin.kiwa.debug"
EXPECTED_ACTIVITY="dev.kirin.kiwa.MainActivity"
COMP="$PKG/$EXPECTED_ACTIVITY"
APK="${APK_ARG:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

resolve_aapt2() {
  if [[ -n "${AAPT2:-}" ]]; then echo "$AAPT2"; return; fi
  local sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
  local found=""
  if [[ -d "$sdk/build-tools" ]]; then
    found="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -1)"
  fi
  [[ -n "$found" ]] && { echo "$found"; return; }
  command -v aapt2 2>/dev/null || true
}

# ---- 2. ビルドと APK metadata の検査（adb を呼ぶ前に行う） ----
if [[ $BUILD -eq 1 && $SKIP_INSTALL -eq 0 && -z "$APK_ARG" ]]; then
  (cd "$ROOT" && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ./bootstrap-gradle.sh :app:assembleDebug -q) \
    > "$OUT/build.log" 2>&1 || { echo "NG: ビルドが落ちた（$OUT/build.log）" >&2; exit 1; }
fi

if [[ $SKIP_INSTALL -eq 0 || -n "$APK_ARG" || -f "$APK" ]]; then
  if [[ ! -f "$APK" ]]; then
    echo "NG: APK が無い: $APK" >&2
    exit 2
  fi
  AAPT2_BIN="$(resolve_aapt2)"
  if [[ -z "$AAPT2_BIN" || ! -x "$AAPT2_BIN" ]]; then
    echo "NG: aapt2 が見つからない（AAPT2 または ANDROID_HOME を確認）" >&2
    exit 2
  fi
  badging="$("$AAPT2_BIN" dump badging "$APK" 2>/dev/null | tr -d '\r')"
  apk_pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
  apk_act="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
  apk_lbl="$(sed -n "s/^application-label:'\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
  if [[ "$apk_pkg" != "$PKG" ]]; then
    echo "NG: APK の package が $PKG ではない（実際: '${apk_pkg:-不明}'）。install せず止める" >&2
    exit 2
  fi
  if [[ "$apk_act" != "$EXPECTED_ACTIVITY" ]]; then
    echo "NG: APK の launchable-activity が $EXPECTED_ACTIVITY ではない（実際: '${apk_act:-不明}'）" >&2
    exit 2
  fi
  if ! grep -q '^application-debuggable$' <<<"$badging"; then
    echo "NG: APK が debuggable ではない" >&2
    exit 2
  fi
  printf 'apk=%s\npackage=%s\nactivity=%s\nlabel=%s\n' "$APK" "$apk_pkg" "$apk_act" "$apk_lbl" > "$OUT/apk-metadata.txt"
fi

# 端末に触る口はこの1つだけ。必ず -s を付ける。
a() { "$ADB" -s "$SERIAL" "$@"; }

fails=()
log() { echo "$*" | tee -a "$OUT/result.txt"; }
fail() { fails+=("$1"); log "NG: $1"; }
pass() { log "OK: $1"; }
: > "$OUT/result.txt"

DEVICE_MODIFIED=0
CLEANED_UP=0
CLEANUP_ERR=0
PUSHED_FILES=()
SETTINGS_STATE=""
PREFS_STATE=""
PREV_DIR_EXISTED=""
TRACE_DIR_EXISTED=""
APPOPS_MODE_BEFORE=""
GBOARD_TOUCHED=0
GBOARD_RESTORED=1
GBOARD_TOGGLES=0
GBOARD_INITIAL_STEPS=""
GBOARD_INITIAL_MODE="untouched"
GBOARD_CURRENT_MODE="unknown"
GBOARD_OBSERVED_RESTORED_STATUS="unprobed"
DEBUG_UID=""
FG_IS_POPUP=0

FG_ERR=""
check_fg_state() {
  FG_IS_POPUP=0
  local win
  win="$(a shell dumpsys window 2>/dev/null | tr -d '\r')"
  if [[ -z "$win" ]]; then
    FG_ERR="dumpsys window が空"
    return 1
  fi
  if grep -Eq 'isKeyguardShowing=true|mDreamingLockscreen=true|mShowingLockscreen=true|mKeyguardShowing=true|mAwake=false|screenState=SCREEN_STATE_OFF' <<<"$win"; then
    FG_ERR="端末がロック中または画面消灯中"
    return 1
  fi
  local focus focused_app
  focus="$(grep -E 'mCurrentFocus=' <<<"$win" | tail -1)"
  focused_app="$(grep -E 'mFocusedApp=' <<<"$win" | tail -1)"
  if grep -qF "$PKG/" <<<"$focus"; then
    if [[ -n "$focused_app" ]] && ! grep -qF "$PKG/" <<<"$focused_app"; then
      FG_ERR="mFocusedApp が $PKG ではない ($focused_app)"
      return 1
    fi
    return 0
  fi
  if grep -qE 'PopupWindow:[0-9a-fA-F]+' <<<"$focus" && grep -qF "$PKG/" <<<"$focused_app"; then
    local popup_tok popup_owner_line
    if [[ -z "$DEBUG_UID" ]]; then
      DEBUG_UID="$(a shell "run-as $PKG id -u" 2>/dev/null | tr -d '\r')"
    fi
    popup_tok="$(grep -oE 'PopupWindow:[0-9a-fA-F]+' <<<"$focus" | head -1)"
    popup_owner_line="$(awk -v tok="$popup_tok" 'index($0, tok) && /Window #[0-9]+ Window\{/{found=1; next} found && /mOwnerUid=.*package=/{print; exit} found && /Window #[0-9]+ Window\{/{exit}' <<<"$win")"
    if [[ "$DEBUG_UID" =~ ^[0-9]+$ ]] \
      && grep -Eq "(^|[[:space:]])mOwnerUid=${DEBUG_UID}([[:space:]]|$)" <<<"$popup_owner_line" \
      && grep -Eq "(^|[[:space:]])package=${PKG}([[:space:]]|$)" <<<"$popup_owner_line"; then
      FG_IS_POPUP=1
      printf 'popup=%s ownerUid=%s package=%s focusedApp=%s\n' \
        "$popup_tok" "$DEBUG_UID" "$PKG" "$focused_app" >> "$OUT/popup-owner.log"
      return 0
    fi
    FG_ERR="PopupWindow の所有者が $PKG (uid=${DEBUG_UID:-不明}) ではない (${focus} / ${popup_owner_line:-owner不明})"
    return 1
  fi
  if grep -qE 'InputMethod' <<<"$focus" && grep -qF "$PKG/" <<<"$focused_app"; then
    local im
    im="$(a shell dumpsys input_method 2>/dev/null | tr -d '\r')"
    if grep -Eq "(mServedPackageName|targetPackage|packageName)=$PKG([^a-zA-Z0-9_.]|$)" <<<"$im"; then
      return 0
    fi
    FG_ERR="IME 前面だが入力先が $PKG ではない"
    return 1
  fi
  FG_ERR="前面が $PKG ではない (${focus:-mCurrentFocusなし} ${focused_app:-})"
  return 1
}

write_gboard_state() {
  printf 'initial_mode=%s\ninitial_steps_to_kana=%s\ntotal_toggles=%d\ncurrent_mode=%s\nobserved_restored_status=%s\nrestored=%d\n' \
    "$GBOARD_INITIAL_MODE" "${GBOARD_INITIAL_STEPS:-unknown}" "$GBOARD_TOGGLES" "$GBOARD_CURRENT_MODE" "${GBOARD_OBSERVED_RESTORED_STATUS:-unprobed}" "$GBOARD_RESTORED" \
    > "$OUT/gboard-state.txt"
}

restore_gboard_mode() {
  if [[ "$GBOARD_TOUCHED" -eq 0 ]]; then
    GBOARD_RESTORED=1
    write_gboard_state
    return 0
  fi
  if [[ -z "$GBOARD_INITIAL_STEPS" ]]; then
    GBOARD_RESTORED=0
    write_gboard_state
    return 1
  fi
  local need=$(( (3 - (GBOARD_TOGGLES % 3)) % 3 ))
  if [[ $need -eq 0 && "$GBOARD_RESTORED" -eq 1 ]]; then
    write_gboard_state
    return 0
  fi
  if ! check_fg_state; then
    GBOARD_RESTORED=0
    write_gboard_state
    return 1
  fi
  if [[ "$FG_IS_POPUP" -eq 1 ]]; then
    a shell input keyevent 111 >/dev/null 2>&1 || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
    sleep 1
    if ! check_fg_state || [[ "$FG_IS_POPUP" -ne 0 ]]; then
      GBOARD_RESTORED=0
      write_gboard_state
      return 1
    fi
  fi
  local i w h coords x y
  read -r w h <<<"$(screen_size)"
  if [[ "${w:-}" != "2560" || "${h:-}" != "1600" ]]; then
    GBOARD_RESTORED=0
    write_gboard_state
    return 1
  fi
  for ((i = 0; i < need; i++)); do
    # 浮いた枠の単一矩形だけを読む。形式変更時は推測せず復元失敗にする。
    coords="$(a shell dumpsys window windows | tr -d '\r' | awk '
      /Window #[0-9]+ Window\{/ {
        ime = /Window\{[^}]* InputMethod\}/; owner = 0; region = ""
      }
      ime && /mOwnerUid=.*package=com\.google\.android\.inputmethod\.latin / { owner = 1 }
      ime && /touchable region=SkRegion\(\([0-9]+,[0-9]+,[0-9]+,[0-9]+\)\)/ {
        region = $0
        sub(/^.*SkRegion\(\(/, "", region); sub(/\)\).*$/, "", region)
      }
      ime && owner && /isVisible=true/ && region != "" {
        split(region, b, ",")
        if (b[1] >= 0 && b[2] >= 0 && b[3] <= 2560 && b[4] <= 1600 &&
            b[3] > b[1] && b[4] > b[2]) {
          x = b[1] + int((b[3] - b[1]) * 11 / 100)
          y = b[2] + int((b[4] - b[2]) * 79 / 100)
          count++
        }
      }
      END { if (count == 1) print x, y; else exit 1 }
    ')" || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
    read -r x y <<<"$coords"
    check_fg_state || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
    a shell input tap "$x" "$y" >/dev/null 2>&1 || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
    GBOARD_TOGGLES=$((GBOARD_TOGGLES + 1))
    sleep 1
  done
  check_fg_state || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  a shell input text a >/dev/null 2>&1 || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  sleep 1
  check_fg_state || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  a shell uiautomator dump "$UI" >/dev/null 2>&1 || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  check_fg_state || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  a exec-out cat "$UI" > "$OUT/ui-gboard-restored.xml"
  a shell "rm -f '$UI'" >/dev/null 2>&1 || true
  check_fg_state || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  a shell input keyevent 67 >/dev/null 2>&1 || { GBOARD_RESTORED=0; write_gboard_state; return 1; }
  sleep 1
  local restored_status
  restored_status="$(grep -o 'content-desc="行 [^"]*"' "$OUT/ui-gboard-restored.xml" 2>/dev/null | head -1)"
  GBOARD_OBSERVED_RESTORED_STATUS="${restored_status:-none}"
  if [[ "$GBOARD_INITIAL_STEPS" -eq 0 && "$restored_status" != *"変換中"* ]]; then
    GBOARD_RESTORED=0; write_gboard_state; return 1
  elif [[ "$GBOARD_INITIAL_STEPS" -gt 0 && "$restored_status" == *"変換中"* ]]; then
    GBOARD_RESTORED=0; write_gboard_state; return 1
  fi
  GBOARD_CURRENT_MODE="$GBOARD_INITIAL_MODE"
  GBOARD_RESTORED=1
  write_gboard_state
  return 0
}

backup_app_file() { # backup_app_file <rel_path> <host_dest> <state_var_name>
  local rel="$1" dst="$2" var="$3" probe empty_check
  if ! probe="$(a shell "run-as $PKG sh -c 'test -f $rel && echo 1 || echo 0'" 2>/dev/null)"; then
    echo "NG: $rel の存在確認に失敗した（run-as 不可）" >&2
    exit 2
  fi
  probe="${probe//$'\r'/}"
  if [[ "$probe" == "1" ]]; then
    if ! a exec-out run-as "$PKG" cat "$rel" > "$dst.tmp" 2>/dev/null; then
      rm -f "$dst.tmp"
      echo "NG: $rel の控え取得に失敗した" >&2
      exit 2
    fi
    if [[ ! -s "$dst.tmp" ]]; then
      empty_check="$(a shell "run-as $PKG sh -c 'test -f $rel && test ! -s $rel && echo 1 || echo 0'" 2>/dev/null | tr -d '\r')" || empty_check=""
      if [[ "$empty_check" != "1" ]]; then
        rm -f "$dst.tmp"
        echo "NG: $rel の控えが空（取得失敗）" >&2
        exit 2
      fi
    fi
    mv "$dst.tmp" "$dst"
    printf -v "$var" '%s' "present"
  elif [[ "$probe" == "0" ]]; then
    printf -v "$var" '%s' "absent"
  else
    echo "NG: $rel の存在確認結果が不正: '${probe}'" >&2
    exit 2
  fi
}

restore_app_file() { # restore_app_file <rel_path> <parent_dir> <host_src> <state>
  local rel="$1" parent="$2" src="$3" state="$4"
  if [[ "$state" == "present" ]]; then
    if ! a shell "run-as $PKG sh -c 'mkdir -p $parent && cat > $rel'" < "$src" >/dev/null 2>&1 \
      || [[ "$(a shell "run-as $PKG sh -c 'test -f $rel && echo 1 || echo 0'" 2>/dev/null | tr -d '\r')" != "1" ]] \
      || ! a exec-out run-as "$PKG" cat "$rel" > "$src.restored" 2>/dev/null \
      || ! cmp -s "$src" "$src.restored"; then
      fail "cleanup: $rel の復元に失敗した"
      return 1
    fi
    if [[ ! -s "$src" ]] && [[ "$(a shell "run-as $PKG sh -c 'test ! -s $rel && echo 1 || echo 0'" 2>/dev/null | tr -d '\r')" != "1" ]]; then
      fail "cleanup: $rel（空ファイル）の復元に失敗した"
      return 1
    fi
  elif [[ "$state" == "absent" ]]; then
    if ! a shell "run-as $PKG rm -f $rel" >/dev/null 2>&1 \
      || [[ "$(a shell "run-as $PKG sh -c 'test -f $rel && echo 1 || echo 0'" 2>/dev/null | tr -d '\r')" != "0" ]]; then
      fail "cleanup: $rel の削除に失敗した"
      return 1
    fi
  else
    fail "cleanup: $rel の開始時状態が不明（${state:-unset}）"
    return 1
  fi
  return 0
}

cleanup_device() {
  [[ "$CLEANED_UP" -eq 1 ]] && return "$CLEANUP_ERR"
  CLEANED_UP=1
  [[ "$DEVICE_MODIFIED" -eq 1 ]] || return 0

  local cleanup_err=0

  if [[ "$GBOARD_TOUCHED" -eq 1 && "$GBOARD_RESTORED" -ne 1 ]]; then
    restore_gboard_mode || cleanup_err=1
  else
    write_gboard_state
  fi
  if [[ "$GBOARD_TOUCHED" -eq 1 ]]; then
    log "Gboard 配列: 開始=$GBOARD_INITIAL_MODE / 終了=$GBOARD_CURRENT_MODE / 切替回数=$GBOARD_TOGGLES / 復元=$GBOARD_RESTORED"
    if [[ "$GBOARD_RESTORED" -ne 1 ]]; then
      fail "Gboard の配列を開始時の状態に戻せなかった（$OUT/gboard-state.txt）"
    fi
  fi

  if ! a shell am force-stop "$PKG" >/dev/null 2>&1; then
    cleanup_err=1
    fail "cleanup: am force-stop $PKG に失敗した"
  fi

  restore_app_file "files/settings.json" "files" "$OUT/backup/settings.json" "${SETTINGS_STATE:-}" || cleanup_err=1
  restore_app_file "shared_prefs/kiwa.xml" "shared_prefs" "$OUT/backup/kiwa.xml" "${PREFS_STATE:-}" || cleanup_err=1

  local prev_after_probe prev_after_list f
  prev_after_probe="$(a shell "run-as $PKG sh -c 'test -d files/previous && echo 1 || echo 0'" 2>/dev/null | tr -d '\r')" || prev_after_probe=""
  if [[ "$prev_after_probe" == "1" ]]; then
    if prev_after_list="$(a shell "run-as $PKG ls -1 files/previous" 2>/dev/null)"; then
      printf '%s\n' "${prev_after_list//$'\r'/}" | sed '/^$/d' | sort > "$OUT/backup/prev-after.txt"
      while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        # `adb shell` は標準入力を読む。切らないと一覧の残りを飲み込み、1件目しか消えない
        # （2026-11-12 の実機で4件残った。それでも cleanup_err は 0 のままだった）。
        if ! a shell "run-as $PKG rm -f 'files/previous/$f'" </dev/null >/dev/null 2>&1; then
          cleanup_err=1
          fail "cleanup: files/previous/$f の削除に失敗した"
        fi
      done < <(comm -13 "$OUT/backup/prev-before.txt" "$OUT/backup/prev-after.txt")
      if [[ "${PREV_DIR_EXISTED:-0}" -eq 0 ]]; then
        a shell "run-as $PKG rmdir files/previous" >/dev/null 2>&1 || true
      fi
    else
      cleanup_err=1
      fail "cleanup: files/previous の一覧取得に失敗した"
    fi
  elif [[ "$prev_after_probe" == "0" ]]; then
    : > "$OUT/backup/prev-after.txt"
    if [[ "${PREV_DIR_EXISTED:-0}" -eq 1 ]]; then
      cleanup_err=1
      fail "cleanup: 開始時に存在した files/previous が消えている"
    fi
  else
    cleanup_err=1
    fail "cleanup: files/previous の確認に失敗した"
  fi

  if [[ "${PREFS_STATE:-}" == "absent" ]]; then
    a shell "run-as $PKG sh -c 'rmdir shared_prefs 2>/dev/null || true'" >/dev/null 2>&1 || true
  fi
  if [[ "${SETTINGS_STATE:-}" == "absent" && "${PREV_DIR_EXISTED:-0}" -eq 0 ]]; then
    a shell "run-as $PKG sh -c 'rmdir files 2>/dev/null || true'" >/dev/null 2>&1 || true
  fi

  if [[ -n "${APPOPS_MODE_BEFORE:-}" && "$APPOPS_MODE_BEFORE" != "allow" ]]; then
    local appops_after=""
    if ! a shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE "$APPOPS_MODE_BEFORE" >/dev/null 2>&1 \
      || ! appops_after="$(a shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE 2>/dev/null)" \
      || ! grep -q "$APPOPS_MODE_BEFORE" <<<"${appops_after//$'\r'/}"; then
      cleanup_err=1
      fail "cleanup: appops MANAGE_EXTERNAL_STORAGE を $APPOPS_MODE_BEFORE に戻せなかった"
    fi
  fi

  local trace_after_probe trace_after_list
  trace_after_probe="$(a shell "test -d '$TRACE_DIR' && echo 1 || echo 0" 2>/dev/null | tr -d '\r')" || trace_after_probe=""
  if [[ "$trace_after_probe" == "1" ]]; then
    if trace_after_list="$(a shell "ls -1 '$TRACE_DIR'" 2>/dev/null)"; then
      printf '%s\n' "${trace_after_list//$'\r'/}" | sed '/^$/d' | sort > "$OUT/trace-after.txt"
      while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        if ! a shell "rm -f '$TRACE_DIR/$f'" </dev/null >/dev/null 2>&1; then
          cleanup_err=1
          fail "cleanup: $TRACE_DIR/$f の削除に失敗した"
        fi
      done < <(comm -13 "$OUT/trace-before.txt" "$OUT/trace-after.txt")
      if [[ "${TRACE_DIR_EXISTED:-0}" -eq 0 ]]; then
        a shell "rmdir '$TRACE_DIR' 2>/dev/null" >/dev/null 2>&1 || true
      fi
    else
      cleanup_err=1
      fail "cleanup: $TRACE_DIR の一覧取得に失敗した"
    fi
  elif [[ "$trace_after_probe" == "0" ]]; then
    : > "$OUT/trace-after.txt"
    if [[ "${TRACE_DIR_EXISTED:-0}" -eq 1 ]]; then
      cleanup_err=1
      fail "cleanup: 開始時に存在した $TRACE_DIR が消えている"
    fi
  else
    cleanup_err=1
    fail "cleanup: $TRACE_DIR の確認に失敗した"
  fi

  if ! a shell "rm -f '$UI'" >/dev/null 2>&1; then
    cleanup_err=1
    fail "cleanup: $UI の削除に失敗した"
  fi
  local pf
  for pf in "${PUSHED_FILES[@]}"; do
    [[ -n "$pf" ]] || continue
    if ! a shell "rm -f '$pf'" >/dev/null 2>&1; then
      cleanup_err=1
      fail "cleanup: $pf の削除に失敗した"
    fi
  done
  if [[ -n "${DIR:-}" ]]; then
    if ! a shell "rmdir '$DIR'" >/dev/null 2>&1; then
      cleanup_err=1
      fail "cleanup: $DIR の削除に失敗した"
    fi
  fi

  CLEANUP_ERR="$cleanup_err"
  printf 'cleanup_err=%d\ndir=%s\nui=%s\n' "$cleanup_err" "${DIR:-}" "${UI:-}" > "$OUT/cleanup.txt"
  if [[ $cleanup_err -ne 0 ]]; then
    fail "終了時の復元または生成物の片付けに失敗した（$OUT/cleanup.txt）"
    return 1
  fi
  return 0
}

trap 'rc=$?; cleanup_device || rc=1; exit "$rc"' EXIT

finish() {
  cleanup_device || true
  if [[ ${#fails[@]} -eq 0 ]]; then log "--- 全部通った"; exit 0; fi
  log "--- 外れ ${#fails[@]} 件"; exit 1
}

require_fg() {
  if ! check_fg_state; then
    fail "前面検査で停止（$1）: $FG_ERR"
    finish
  fi
}

# ---- 3. 端末の事前条件と導入 ----
state="$(a get-state 2>/dev/null | tr -d '\r')"
if [[ "$state" != "device" ]]; then
  echo "NG: 端末 $SERIAL が接続されていない（get-state='${state:-none}'）" >&2
  exit 2
fi

init_win="$(a shell dumpsys window 2>/dev/null | tr -d '\r')"
if [[ -z "$init_win" ]] || grep -Eq 'isKeyguardShowing=true|mDreamingLockscreen=true|mShowingLockscreen=true|mKeyguardShowing=true|mAwake=false|screenState=SCREEN_STATE_OFF' <<<"$init_win"; then
  echo "NG: 端末 $SERIAL がロック中または画面消灯中のため開始しない" >&2
  exit 2
fi

if [[ $SKIP_INSTALL -eq 0 ]]; then
  # install の人間確認を自動押下しない。失敗したら止める。
  if ! a install -r "$APK" > "$OUT/install.log" 2>&1; then
    echo "NG: 入れられなかった（$OUT/install.log。端末側の確認が必要な場合は手で入れて --skip-install を使う）" >&2
    exit 2
  fi
fi

pkg_info="$(a shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
if ! grep -qF "Package [$PKG]" <<<"$pkg_info" || ! grep -q "DEBUGGABLE" <<<"$pkg_info"; then
  echo "NG: 端末 $SERIAL に debug パッケージ $PKG が入っていない" >&2
  exit 2
fi
DEBUG_UID="$(sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' <<<"$pkg_info" | head -1)"

RUN_ID="kiwa-device-test-$(date +%Y%m%d-%H%M%S)-$$"
DIR="/sdcard/Download/$RUN_ID"
TRACE_DIR="/sdcard/Android/data/$PKG/files/Documents/kiwa"
UI="/sdcard/Download/${RUN_ID}-ui.xml"

mkdir -p "$OUT/backup"
if ! APPOPS_BEFORE="$(a shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE 2>/dev/null)"; then
  echo "NG: appops の取得に失敗した" >&2
  exit 2
fi
APPOPS_BEFORE="${APPOPS_BEFORE//$'\r'/}"
printf '%s\n' "$APPOPS_BEFORE" > "$OUT/backup/appops-before.txt"
if grep -q "allow" <<<"$APPOPS_BEFORE"; then
  APPOPS_MODE_BEFORE="allow"
elif grep -q "ignore" <<<"$APPOPS_BEFORE"; then
  APPOPS_MODE_BEFORE="ignore"
elif grep -q "deny" <<<"$APPOPS_BEFORE"; then
  APPOPS_MODE_BEFORE="deny"
elif grep -q "default" <<<"$APPOPS_BEFORE"; then
  APPOPS_MODE_BEFORE="default"
else
  echo "NG: appops の状態が不明: '${APPOPS_BEFORE:-空}'" >&2
  exit 2
fi

backup_app_file "files/settings.json" "$OUT/backup/settings.json" SETTINGS_STATE
backup_app_file "shared_prefs/kiwa.xml" "$OUT/backup/kiwa.xml" PREFS_STATE

if ! prev_probe="$(a shell "run-as $PKG sh -c 'test -d files/previous && echo 1 || echo 0'" 2>/dev/null)"; then
  echo "NG: files/previous の確認に失敗した（run-as 不可）" >&2
  exit 2
fi
prev_probe="${prev_probe//$'\r'/}"
if [[ "$prev_probe" == "1" ]]; then
  PREV_DIR_EXISTED=1
  if ! prev_list="$(a shell "run-as $PKG ls -1 files/previous" 2>/dev/null)"; then
    echo "NG: files/previous の一覧取得に失敗した" >&2
    exit 2
  fi
  printf '%s\n' "${prev_list//$'\r'/}" | sed '/^$/d' | sort > "$OUT/backup/prev-before.txt"
elif [[ "$prev_probe" == "0" ]]; then
  PREV_DIR_EXISTED=0
  : > "$OUT/backup/prev-before.txt"
else
  echo "NG: files/previous の確認結果が不正: '${prev_probe}'" >&2
  exit 2
fi

if ! trace_probe="$(a shell "test -d '$TRACE_DIR' && echo 1 || echo 0" 2>/dev/null)"; then
  echo "NG: $TRACE_DIR の確認に失敗した" >&2
  exit 2
fi
trace_probe="${trace_probe//$'\r'/}"
if [[ "$trace_probe" == "1" ]]; then
  TRACE_DIR_EXISTED=1
  if ! trace_list="$(a shell "ls -1 '$TRACE_DIR'" 2>/dev/null)"; then
    echo "NG: $TRACE_DIR の一覧取得に失敗した" >&2
    exit 2
  fi
  printf '%s\n' "${trace_list//$'\r'/}" | sed '/^$/d' | sort > "$OUT/trace-before.txt"
elif [[ "$trace_probe" == "0" ]]; then
  TRACE_DIR_EXISTED=0
  : > "$OUT/trace-before.txt"
else
  echo "NG: $TRACE_DIR の確認結果が不正: '${trace_probe}'" >&2
  exit 2
fi

if [[ "$(a shell "test ! -e '$DIR' && test ! -e '$UI' && mkdir '$DIR' && echo ok" 2>/dev/null | tr -d '\r')" != "ok" ]]; then
  echo "NG: 一意な fixture ディレクトリ $DIR を新規作成できなかった（既存データは消さない）" >&2
  exit 2
fi
DEVICE_MODIFIED=1

if ! a shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1; then
  fail "appops MANAGE_EXTERNAL_STORAGE allow の設定に失敗した"
  finish
fi

# ---- 見本とその期待（中身は台本の中に固定で持つ。UTF-8・LF・BOM なし） ----
# カーソルは開いた直後の位置 = 行 1・桁 1（先頭）。そこへ `abc` と `にほんご` が入る。
printf 'いろはにほへと\nちりぬるを\nわかよたれそ\n' > "$OUT/edit.txt.sent"
printf 'あいうえお\nかきくけこ\nさしすせそ\n' > "$OUT/open-only.txt.sent"
printf 'abcにほんごいろはにほへと\nちりぬるを\nわかよたれそ\n' > "$OUT/edit.txt.expected"

# 段1 W1 の見本。typewriter 用の長い本文（300 行）と、折り返しの上限の前後（1,500 行 / 2,500 行。1 行目だけ長い）。
for i in $(seq 1 300); do printf '第%03d行　いろはにほへとちりぬるをわかよたれそ\n' "$i"; done > "$OUT/long.txt.sent"
{
  long=""; for i in $(seq 1 20); do long="${long}あいうえおかきくけこさしすせそたちつてと"; done
  for n in 1500 2500; do
    { echo "$long"; for i in $(seq 2 $n); do echo "行$i"; done; } > "$OUT/wrap$n.txt.sent"
  done
}

# 段1 W2 の見本と期待（UTF-8・LF・BOM なし）。
printf 'はるはあけぼの\nなつはよる\n' > "$OUT/w2-wp.txt.sent"
printf 'にほんごはるはあけぼの\nなつはよる\n' > "$OUT/w2-wp.txt.expected1"
printf 'にほんごにほんごはるはあけぼの\nなつはよる\n' > "$OUT/w2-wp.txt.expected2"
printf 'あきはゆふぐれ\nふゆはつとめて\n' > "$OUT/w2-untouched.txt.sent"
printf 'つき日は百代の過客にして\n行かふ年も又旅人也\n' > "$OUT/w2-tab2.txt.sent"
printf 'にほんごつき日は百代の過客にして\n行かふ年も又旅人也\n' > "$OUT/w2-tab2.txt.expected"
printf 'いろはにほへと\nちりぬるを\n' > "$OUT/w2-resume.txt.sent"
printf 'いろはにほへと\nにほんごちりぬるを\n' > "$OUT/w2-resume.txt.expected"
printf 'fun main() {\n}\n' > "$OUT/w2-code.kt.sent"
printf 'あかさたな\nはまやらわ\n' > "$OUT/w2-composing.txt.sent"
printf 'にほんごあかさたな\nはまやらわ\n' > "$OUT/w2-composing.txt.expected-settled"
printf 'にほんごてすとあかさたな\nはまやらわ\n' > "$OUT/w2-composing.txt.unsettled"

# ---- 画面の読み取りと入力（必ず直前に前面とロック状態を検査する） ----
inp() {
  require_fg "input $*"
  a shell input "$@"
}
dump() { # dump <名前>  → $OUT/ui-<名前>.xml と $XML
  local n
  for n in 1 2 3; do
    require_fg "dump $1"
    if a shell uiautomator dump "$UI" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  require_fg "dump-cat $1"
  a exec-out cat "$UI" > "$OUT/ui-$1.xml"
  a shell "rm -f '$UI'" >/dev/null 2>&1 || true
  XML="$OUT/ui-$1.xml"
}
shot() {
  require_fg "shot $1"
  a exec-out screencap -p > "$OUT/$1.png"
}
status() { grep -o 'content-desc="行 [^"]*"' "$XML" | head -1; }
body() { # 本文（Sora が uiautomator に text を出す。改行は &#10;）
  grep -o '<node [^>]*CodeEditor[^>]*>' "$XML" | head -1 | sed -E 's/.* text="([^"]*)".*/\1/'
}
bounds_of() { # bounds_of <node の絞り込み grep 式> → "l t r b"
  grep -o '<node [^>]*>' "$XML" | grep -E "$1" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}
screen_size() { # "幅 高さ"（向きに合わせる。根の bounds）
  grep -o 'bounds="\[0,0\]\[[0-9]*,[0-9]*\]"' "$XML" | head -1 | sed -E 's/.*\]\[([0-9]+),([0-9]+)\].*/\1 \2/'
}
count_of() { # count_of <ui-xml> → 「文字数 N」または「文字数 S / N」（状態表示の左）
  grep -o 'content-desc="行 [^"]*"' "$1" | head -1 | grep -o '文字数 [0-9]*\( / [0-9]*\)\?'
}
kbd_shown() { a shell dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; }
has_node() { grep -qE "$1" "$XML"; } # has_node <node の絞り込み grep 式>
tap_node() { # tap_node <grep 式> → ノードの中心を押す。無ければ 1
  read -r l t r b <<<"$(bounds_of "$1")"
  [[ -n "${l:-}" ]] || return 1
  inp tap $(((l + r) / 2)) $(((t + b) / 2))
}
menu_run() { # menu_run <メニュー名> <コマンド名> → メニューバーからコマンドを押す
  dump menu-a
  tap_node "text=\"$1\"" || return 1
  sleep 1
  dump menu-b
  tap_node "content-desc=\"$2\"" || return 1
  sleep 1
}
tap_editor_start() { # 本文の左上（1 行目の頭）を押す
  dump editor-box
  read -r l t r b <<<"$(bounds_of 'CodeEditor')"
  inp tap $((l + 300)) $((t + 14))
  sleep 1
  inp keycombination 113 122   # Ctrl+Home（左端を直に押すと端のジェスチャーに取られる）
  sleep 1
}
chrome_gone() { # メニューバー・タブ列・ステータスバーが UI の木に無い（$XML を見る）
  ! has_node 'text="ファイル"' && ! has_node 'content-desc="新しいタブ"' && ! has_node 'content-desc="行 ' \
    && ! has_node 'content-desc="モード '
}
open_in_kiwa() { # open_in_kiwa <path> [trace]
  local extra=()
  [[ "${2:-}" == trace ]] && extra=(--ez kiwa.debug.trace true)
  a shell am force-stop "$PKG"
  a shell am start -W -n "$COMP" --es kiwa.debug.open "$1" "${extra[@]}" >/dev/null
  sleep 2
  require_fg "open_in_kiwa $1"
}
# Gboard のキーは UI の木に出ない。横2560x1600の単一矩形の浮いた枠だけを扱う。
# 枠の左から11%・上から79%は現行画像と前回の実測値で確認。形式が変わったら推測せず停止する。
toggle_mode() {
  local coords x y
  read -r w h <<<"$(screen_size)"
  if [[ "${w:-}" != "2560" || "${h:-}" != "1600" ]]; then
    fail "Gboard 切替座標は横 2560x1600 専用のため停止（実際: ${w:-?}x${h:-?}）"
    finish
  fi
  coords="$(a shell dumpsys window windows | tr -d '\r' | awk '
      /Window #[0-9]+ Window\{/ {
        ime = /Window\{[^}]* InputMethod\}/; owner = 0; region = ""
      }
      ime && /mOwnerUid=.*package=com\.google\.android\.inputmethod\.latin / { owner = 1 }
      ime && /touchable region=SkRegion\(\([0-9]+,[0-9]+,[0-9]+,[0-9]+\)\)/ {
        region = $0
        sub(/^.*SkRegion\(\(/, "", region); sub(/\)\).*$/, "", region)
      }
      ime && owner && /isVisible=true/ && region != "" {
        split(region, b, ",")
        if (b[1] >= 0 && b[2] >= 0 && b[3] <= 2560 && b[4] <= 1600 &&
            b[3] > b[1] && b[4] > b[2]) {
          x = b[1] + int((b[3] - b[1]) * 11 / 100)
          y = b[2] + int((b[4] - b[2]) * 79 / 100)
          count++
        }
      }
      END { if (count == 1) print x, y; else exit 1 }
    ')" || {
    fail "Gboard の可視の枠（単一矩形）を読めないため停止"
    finish
  }
  read -r x y <<<"$coords"
  GBOARD_TOUCHED=1
  GBOARD_RESTORED=0
  GBOARD_TOGGLES=$((GBOARD_TOGGLES + 1))
  GBOARD_CURRENT_MODE="toggled($GBOARD_TOGGLES)"
  inp tap "$x" "$y"
  sleep 1
}

# ---- 3b. 見本を送る ----
for name in edit.txt open-only.txt long.txt wrap1500.txt wrap2500.txt \
  w2-wp.txt w2-untouched.txt w2-tab2.txt w2-resume.txt w2-code.kt w2-composing.txt; do
  PUSHED_FILES+=("$DIR/$name")
  a push "$OUT/$name.sent" "$DIR/$name" >/dev/null 2>&1 || { fail "fixture の push に失敗: $name"; finish; }
done
a shell "cd '$DIR' && sha256sum edit.txt open-only.txt && stat -c '%Y %n' edit.txt open-only.txt" \
  | tr -d '\r' > "$OUT/sent-state.txt"
sha_before="$(grep -E '^[0-9a-f]{64}  open-only.txt$' "$OUT/sent-state.txt")"
mtime_before="$(grep -E '^[0-9]+ open-only.txt$' "$OUT/sent-state.txt")"

# ---- 4. 開くだけ（何も打たない） ----
open_in_kiwa "$DIR/open-only.txt"
dump open-only
shot open-only
sleep 3
a shell am force-stop "$PKG"

# ---- 5. 書く ----
open_in_kiwa "$DIR/edit.txt" trace
dump opened
shot opened
read -r l t r b <<<"$(bounds_of 'CodeEditor')"
# 本文の1行目を押してキーボードを出し、Ctrl+Home で先頭へ置く。`.txt` はワープロで開き行番号の列が無いので、
# 本文の左端を押すと画面の端のジェスチャーに取られてキーボードが出ない（実測: x が 22px 以下で出ない）。
# 桁 1 になったことを確かめる（カーソルの位置を期待に固定しているため）。
inp tap $((l + 300)) $((t + 14))
sleep 1
inp keycombination 113 122
sleep 2
dump tapped
if [[ "$(status)" == *"行 1, 桁 1 "* ]]; then
  pass "カーソルが開いた直後の位置（行 1, 桁 1）にある"
else
  fail "カーソルが行 1, 桁 1 にならなかった: $(status)"
  finish
fi

# Gboard を「かな」にそろえる。かなだけが `a` を変換中にする。**そうでなければ切り替えて、また試す。**
# （モードはアプリを止めても残る。見分ける手がかりは変換中の有無しか無い）
kana=0
for i in 1 2 3; do
  inp text a
  sleep 1
  dump probe$i
  composing=0
  [[ "$(status)" == *"変換中"* ]] && composing=1
  inp keyevent 67   # 試し打ちの a を消す
  sleep 1
  if [[ $composing -eq 1 ]]; then
    kana=1
    GBOARD_INITIAL_STEPS=$((i - 1))
    if [[ $GBOARD_INITIAL_STEPS -eq 0 ]]; then
      GBOARD_INITIAL_MODE="kana"
    else
      GBOARD_INITIAL_MODE="non-kana(steps_to_kana=$GBOARD_INITIAL_STEPS)"
    fi
    GBOARD_CURRENT_MODE="kana"
    break
  fi
  toggle_mode
done
if [[ $kana -ne 1 ]]; then
  fail "Gboard を日本語（かな）にできなかった（a が変換中にならない）。流す前に Gboard を日本語にしておく"
  shot gboard-not-kana
  finish
fi
dump probe-erased
if [[ "$(body)" == "いろはにほへと&#10;"* ]]; then
  pass "試し打ちを消して、本文は開いた直後に戻った"
else
  fail "試し打ちが本文に残った: $(body)"
  finish
fi

# かな → 英字。`abc` はそのままの字（変換中にならない）で入る。
toggle_mode
inp text abc
sleep 1
dump abc
shot abc
if [[ "$(body)" == "abcいろはにほへと&#10;"* && "$(status)" != *"変換中"* ]]; then
  pass "英字で abc が入った（変換中ではない）"
  GBOARD_CURRENT_MODE="latin"
else
  fail "abc が英字で入らなかった: $(body) / $(status)"
  finish
fi

# 英字 → 数字 → かな。`nihongo` は「にほんご」が変換中になる。
toggle_mode
toggle_mode
inp text nihongo
sleep 1
dump composing
shot composing
if [[ "$(status)" == *"変換中"* ]]; then
  pass "nihongo が変換中になった"
  GBOARD_CURRENT_MODE="kana"
else
  fail "nihongo が変換中にならなかった: $(body) / $(status)"
  finish
fi

# Enter で「にほんご」のまま確定する。確定せず改行になったら、ここで止めて報告する。
inp keyevent 66
sleep 1
dump committed
shot committed
if [[ "$(body)" == "abcにほんごいろはにほへと&#10;"* && "$(status)" != *"変換中"* ]]; then
  pass "Enter で にほんご のまま確定した"
else
  fail "Enter で確定しなかった: $(body) / $(status)"
  finish
fi

# ---- 6. 保存（メニューバーの「保存」。記号キー列の同名のボタンは content-desc を持つので除く） ----
read -r l t r b <<<"$(bounds_of 'text="保存"[^>]*content-desc=""')"
if [[ -z "${l:-}" ]]; then
  fail "メニューバーの「保存」が UI の木に無い"
  finish
fi
inp tap $(((l + r) / 2)) $(((t + b) / 2))
sleep 2
dump saved
if [[ "$(status)" != *"未保存"* ]]; then
  pass "保存を押して未保存が消えた"
else
  fail "保存を押しても未保存のまま: $(status)"
fi

# 計測は止める前に取る（150ms ごとに書き出される）。
sleep 1
new_trace="$(a shell "ls -1 '$TRACE_DIR' 2>/dev/null" | tr -d '\r' | sort | comm -13 "$OUT/trace-before.txt" - | tail -1)"
[[ -n "$new_trace" ]] && a pull "$TRACE_DIR/$new_trace" "$OUT/trace.jsonl" >/dev/null 2>&1

# ---- 7. 止めて起こし直し、開き直す ----
open_in_kiwa "$DIR/edit.txt"
dump reopened
shot reopened

# ---- 8. 判定 ----
a pull "$DIR/edit.txt" "$OUT/edit.txt.pulled" >/dev/null 2>&1
if cmp -s "$OUT/edit.txt.pulled" "$OUT/edit.txt.expected"; then
  pass "edit.txt が期待のバイトと一致する"
else
  fail "edit.txt が期待のバイトと違う（cmp: $(cmp "$OUT/edit.txt.pulled" "$OUT/edit.txt.expected" 2>&1 | head -1)）"
fi

a pull "$DIR/open-only.txt" "$OUT/open-only.txt.pulled" >/dev/null 2>&1
sha_after="$(a shell "cd '$DIR' && sha256sum open-only.txt" | tr -d '\r')"
mtime_after="$(a shell "cd '$DIR' && stat -c '%Y %n' open-only.txt" | tr -d '\r')"
if [[ -n "$sha_before" && "$sha_after" == "$sha_before" ]]; then
  pass "open-only.txt の sha256 が送った直後と同じ"
else
  fail "open-only.txt の sha256 が変わった（前 '$sha_before' / 後 '$sha_after'）"
fi
if [[ -n "$mtime_before" && "$mtime_after" == "$mtime_before" ]]; then
  pass "open-only.txt の更新時刻が送った直後と同じ"
else
  fail "open-only.txt の更新時刻が変わった（前 '$mtime_before' / 後 '$mtime_after'）"
fi

# 「にほんご」の変換中の範囲は、abc の直後の 3〜7。試し打ちの あ（0〜1）とは区別できる。
if grep -q '"event":"composing.range","start":3,"end":7' "$OUT/trace.jsonl" 2>/dev/null; then
  pass "計測に変換中の記録（boundary composing.range 3〜7）がある"
else
  fail "計測に変換中の記録（boundary composing.range 3〜7）が無い（${new_trace:-JSONL なし}）"
fi

for f in composing reopened; do
  if [[ -s "$OUT/$f.png" ]]; then pass "画像 $f.png がある"; else fail "画像 $f.png が無い"; fi
done

# ======================================================================
# 段1 W1: ワープロ（`.txt` はワープロで開く。上の流れは全部ワープロのタブの上で通っている）
# ======================================================================

# ---- 9. モード名と文字数（上で撮った画面から読む） ----
XML="$OUT/ui-opened.xml"
if has_node 'text="ワープロ・横"[^>]*content-desc="モード ワープロ・横"'; then
  pass ".txt を開くとステータスバーに「ワープロ」が出る"
else
  fail ".txt を開いてもステータスバーに「ワープロ」が出ない"
fi
# 期待: いろはにほへと(7) ちりぬるを(5) わかよたれそ(6) = 18。abc(3) + にほんご(4) を足して 25。
# 変換中の にほんご は数えない（21）。
for pair in "opened:文字数 18" "composing:文字数 21" "committed:文字数 25" "reopened:文字数 25"; do
  name="${pair%%:*}"; want="${pair#*:}"; got="$(count_of "$OUT/ui-$name.xml")"
  if [[ "$got" == "$want" ]]; then pass "文字数が期待どおり（$name: $want。変換中の分は数えない）"; else fail "文字数が違う（$name: 期待「$want」/ 実際「$got」）"; fi
done
shot wp-screen

# ---- 10. view.mode（メニューの「表示」→「モードを切り替える」。札を押しても同じコマンドが走る） ----
dump m0; body_before="$(body)"
if menu_run "表示" "モードを切り替える"; then
  dump m1
  if has_node 'content-desc="モード コード"' && [[ -z "$(count_of "$XML")" ]]; then
    pass "view.mode で「コード」に変わり、文字数が消えた"
  else
    fail "view.mode で「コード」にならなかった: $(status)"
  fi
  [[ "$(body)" == "$body_before" ]] && pass "モードを切り替えても本文が変わらない" || fail "モードを切り替えたら本文が変わった"
  shot code-screen
  if tap_node 'content-desc="モード コード"'; then
    sleep 1; dump m2
    has_node 'content-desc="モード ワープロ・横"' && [[ "$(count_of "$XML")" == "文字数 25" ]] \
      && pass "ステータスバーのモード名を押すと「ワープロ」に戻る" || fail "モード名を押しても「ワープロ」に戻らなかった"
  else
    fail "ステータスバーのモード名の札が UI の木に無い"
  fi
else
  fail "メニューから「モードを切り替える」を押せなかった"
fi

# ---- 11. 選択の文字数（Shift+End = 1行目 abcにほんごいろはにほへと の 14 字） ----
tap_editor_start
# 間を置かずに押すと Shift が効かず、カーソルが動くだけになる。-t で押している時間を持たせる（実測）。
# Shift+→ を繰り返すと押すたびに選択が始め直しになるので、1回で伸ばせる Shift+End を使う。
inp keycombination -t 100 59 123
sleep 1; dump sel
if [[ "$(count_of "$XML")" == "文字数 14 / 25" ]]; then pass "選択中は「選択 / 全体」（文字数 14 / 25）"; else fail "選択の文字数が違う: $(count_of "$XML")"; fi

# ---- 12. IME の4つのうち AVD で分かる所（ワープロのタブ）。変換中の飾り = composing.png、取り消し・Tab はここ ----
tap_editor_start
dump u0; body_u0="$(body)"
inp text nihongo; sleep 1
inp keyevent 61   # Tab
sleep 1; dump tab
if [[ "$(body)" == "にほんご"* && "$(body)" != *"&#9;"* && "$(status)" == *"変換中"* ]]; then
  pass "変換中の Tab は本文に入らず、変換中のまま"
else
  fail "変換中の Tab が効いた: $(body) / $(status)"
fi
inp keyevent 66; sleep 1; dump tabcommit
kbd_shown && { inp keyevent 4; sleep 1; }  # キーボードを畳む（IME が取る。出ていない時に戻るを送るとアプリが閉じる）
dump kbd; tap_node 'text="戻す"'; sleep 1; dump undo
if [[ "$(body)" == "$body_u0" ]]; then pass "1回の変換が1回の取り消し（戻す1回で変換前の本文に戻った）"; else fail "戻す1回で戻らなかった: $(body)"; fi

# ---- 13. 集中の画面 ----
if menu_run "表示" "集中する"; then
  dump focus; shot focus
  if chrome_gone && has_node 'text="文字数 [0-9]+"' && has_node 'CodeEditor'; then
    pass "集中の画面: メニューバー・タブ列・ステータスバーが UI の木から消え、文字数は見える"
  else
    fail "集中の画面の木が期待と違う"
  fi
  # 戻る。ソフトキーボードが出ている間の最初の戻るは IME が取る（畳む）。出るまでの戻るの回数を記録する。
  backs=0
  for i in 1 2 3; do
    inp keyevent 4; backs=$((backs + 1)); sleep 1; dump back$i
    if has_node 'content-desc="モード ワープロ・横"'; then break; fi
  done
  if has_node 'content-desc="モード ワープロ・横"'; then pass "戻るで集中の画面から元に戻る（戻る $backs 回。ソフトキーボードが出ていれば最初の1回は IME が取る）"; else fail "戻るで集中の画面から出られなかった"; fi
  # Esc（adb の keyevent。物理キーボードの Esc とは入口が違う可能性があり、実物の確認は実機で行う）。
  if menu_run "表示" "集中する"; then
    dump f1; chrome_gone || fail "2回目の集中の画面に入れなかった"
    inp keyevent 111; sleep 1; dump esc
    has_node 'content-desc="モード ワープロ・横"' && pass "Esc で集中の画面から元に戻る" || fail "Esc で集中の画面から出られなかった"
  else
    fail "2回目の集中の画面に入れなかった"
  fi
  # 変換中の Esc は IME のもの（変換が取り消され、集中の画面は出ない）。
  if menu_run "表示" "集中する"; then
    tap_editor_start
    dump esc-before; body_esc="$(body)"
    inp text nihongo; sleep 1
    dump esc-during
    [[ "$(body)" == "にほんご"* ]] || fail "集中の画面で変換中にならなかった: $(body)"
    inp keyevent 111; sleep 1; dump esc-composing
    if chrome_gone && [[ "$(body)" == "$body_esc" ]] && has_node 'text="文字数 25"'; then
      pass "変換中の Esc は IME が取り（変換が消え）、集中の画面は出ない。文字数も 25 のまま"
    else
      fail "変換中の Esc の扱いが期待と違う: $(body)"
    fi
    for i in 1 2 3; do inp keyevent 4; sleep 1; dump back2-$i; has_node 'content-desc="モード ワープロ・横"' && break; done
  else
    fail "3回目の集中の画面に入れなかった"
  fi
else
  fail "メニューから「集中する」を押せなかった"
fi

# ---- 14. タイプライター（300 行の長い本文。末尾で打つと中ほどに来る / 先頭近くは寄せない） ----
open_in_kiwa "$DIR/long.txt"
tap_editor_start
dump tw0
read -r l t r b <<<"$(bounds_of 'CodeEditor')"
eh=$((b - t))
ink_box() { # ink_box <画像> → "高さ 上端"（本文の字がある左半分の範囲。左端 30px のカーソルと右下のフローティング Gboard を避ける）
  magick "$1" -crop "$(((r - l) / 2 - 30))x${eh}+$((l + 30))+${t}" +repage -fuzz 12% -trim -format '%h %Y' info: 2>/dev/null
}
if command -v magick >/dev/null; then
  inp keycombination 113 123; sleep 1   # Ctrl+End
  inp keyevent 66; sleep 2              # Enter（打つ代わり。保存はしない）
  shot typewriter-end
  read -r h y0 <<<"$(ink_box "$OUT/typewriter-end.png")"; y0="${y0#+}"
  blank_below=$((eh - y0 - h))
  if [[ -n "${h:-}" && $blank_below -ge $((eh / 4)) && $blank_below -le $((eh * 75 / 100)) ]]; then
    pass "タイプライター: 末尾で打つと本文の下に画面の ${blank_below}/${eh}px が空く（中ほどに来た。張り付くと 0 に近い）"
  else
    fail "タイプライター（末尾）: 本文の下の空きが ${blank_below:-?}/${eh}px"
  fi
  inp keycombination 113 122; sleep 1   # Ctrl+Home
  inp keyevent 66; sleep 2
  shot typewriter-start
  read -r h y0 <<<"$(ink_box "$OUT/typewriter-start.png")"; y0="${y0#+}"
  if [[ -n "${h:-}" && $y0 -le 130 ]]; then
    pass "タイプライター: 先頭近くは中ほどへ寄せない（本文の上端が画面の上から ${y0}px）"
  else
    fail "タイプライター（先頭）: 本文の上端が ${y0:-?}px"
  fi
else
  fail "ImageMagick（magick）が無く、タイプライターの画像判定ができない"
fi

# ---- 15. 折り返しの上限（判定はしない。画像を残す。見方は報告書） ----
for n in 1500 2500; do
  open_in_kiwa "$DIR/wrap$n.txt"
  shot wrap$n
done
pass "折り返しの画像 wrap1500.png / wrap2500.png を残した（目で見る）"

# 開いただけの見本・書かなかった見本が変わっていないこと（段0 と同じ判定を、増やした見本にも）
a shell "cd '$DIR' && sha256sum long.txt wrap1500.txt wrap2500.txt" | tr -d '\r' > "$OUT/after-state.txt"
for n in long wrap1500 wrap2500; do
  want="$(sha256sum "$OUT/$n.txt.sent" | cut -d' ' -f1)"
  grep -q "^$want  $n.txt$" "$OUT/after-state.txt" && pass "$n.txt は書き換わっていない" || fail "$n.txt が書き換わった"
done

# ======================================================================
# 段1 W2: 失わない・続きから（離席保存・上書き前の版1世代・起動時の続きから）
# ======================================================================

# 試験用 debug 設定だけで autoSave OFF を確定させる（終了時は cleanup_device が開始前の状態へ戻す）。
a shell am force-stop "$PKG"
if ! a shell "run-as $PKG sh -c 'mkdir -p files && printf \"{\\\"autoSave\\\":false}\\n\" > files/settings.json'" >/dev/null 2>&1 \
  || ! a exec-out run-as "$PKG" cat files/settings.json > "$OUT/w2-settings-autosave-off.json" 2>/dev/null \
  || ! grep -q '"autoSave":false' "$OUT/w2-settings-autosave-off.json"; then
  fail "W2: debug の files/settings.json で autoSave OFF を設定・確認できなかった"
  finish
fi

a shell "ls -1A '$DIR'" | tr -d '\r' | sort > "$OUT/w2-dir-before.txt"
a shell "cd '$DIR' && sha256sum w2-wp.txt w2-untouched.txt w2-tab2.txt w2-resume.txt w2-code.kt w2-composing.txt && stat -c '%Y %n' w2-wp.txt w2-untouched.txt w2-tab2.txt w2-resume.txt w2-code.kt w2-composing.txt" \
  | tr -d '\r' > "$OUT/w2-initial-state.txt"
w2_wp_mtime0="$(grep -E '^[0-9]+ w2-wp\.txt$' "$OUT/w2-initial-state.txt" | cut -d' ' -f1)"
w2_untouched_sha0="$(grep -E '^[0-9a-f]{64}  w2-untouched\.txt$' "$OUT/w2-initial-state.txt")"
w2_untouched_mtime0="$(grep -E '^[0-9]+ w2-untouched\.txt$' "$OUT/w2-initial-state.txt")"
w2_code_mtime0="$(grep -E '^[0-9]+ w2-code\.kt$' "$OUT/w2-initial-state.txt" | cut -d' ' -f1)"

# ---- 16. W2-1: autoSave OFF でワープロを編集 → 10秒待っても不変 → HOME で期待バイトに保存、開いただけの別ファイルは不変 ----
open_in_kiwa "$DIR/w2-untouched.txt"
a shell am start -W --activity-single-top -n "$COMP" --es kiwa.debug.open "$DIR/w2-wp.txt" >/dev/null
sleep 2
require_fg "w2-1 open w2-wp.txt"
tap_editor_start
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-1-edited
shot w2-1-edited
if [[ "$(body)" != "にほんごはるはあけぼの&#10;なつはよる&#10;" || "$(status)" != *"● 未保存"* ]]; then
  fail "W2-1: w2-wp.txt の編集状態が期待と違う: $(body) / $(status)"
fi

# 10 秒待っても時間で保存しないことを確かめる
sleep 10
a pull "$DIR/w2-wp.txt" "$OUT/w2-wp.txt.after10s.pulled" >/dev/null 2>&1
w2_wp_mtime_10s="$(a shell "stat -c '%Y' '$DIR/w2-wp.txt'" | tr -d '\r')"
if cmp -s "$OUT/w2-wp.txt.after10s.pulled" "$OUT/w2-wp.txt.sent" && [[ -n "$w2_wp_mtime0" && "$w2_wp_mtime_10s" == "$w2_wp_mtime0" ]]; then
  pass "W2-1a: autoSave OFF でワープロを編集後 10 秒待ってもファイルのバイトと更新時刻が変わらない"
else
  fail "W2-1a: 10 秒待機中に w2-wp.txt が変わった（mtime: 前 '$w2_wp_mtime0' / 後 '$w2_wp_mtime_10s'）"
fi

# HOME で画面を離れる（ホームや他アプリの UI XML / 画像は取らない）
inp keyevent 3
sleep 2
a pull "$DIR/w2-wp.txt" "$OUT/w2-wp.txt.home1.pulled" >/dev/null 2>&1
if cmp -s "$OUT/w2-wp.txt.home1.pulled" "$OUT/w2-wp.txt.expected1"; then
  pass "W2-1b: ワープロを編集後に HOME で画面を離れると期待のバイトに保存される"
else
  fail "W2-1b: HOME 後の w2-wp.txt が期待のバイトと違う（cmp: $(cmp "$OUT/w2-wp.txt.home1.pulled" "$OUT/w2-wp.txt.expected1" 2>&1 | head -1)）"
fi

a pull "$DIR/w2-untouched.txt" "$OUT/w2-untouched.txt.home1.pulled" >/dev/null 2>&1
w2_untouched_sha1="$(a shell "cd '$DIR' && sha256sum w2-untouched.txt" | tr -d '\r')"
w2_untouched_mtime1="$(a shell "cd '$DIR' && stat -c '%Y %n' w2-untouched.txt" | tr -d '\r')"
if cmp -s "$OUT/w2-untouched.txt.home1.pulled" "$OUT/w2-untouched.txt.sent" \
  && [[ -n "$w2_untouched_sha0" && "$w2_untouched_sha1" == "$w2_untouched_sha0" ]] \
  && [[ -n "$w2_untouched_mtime0" && "$w2_untouched_mtime1" == "$w2_untouched_mtime0" ]]; then
  pass "W2-1c: 開いただけの別ファイル（w2-untouched.txt）は HOME 後もバイトと更新時刻が変わらない"
else
  fail "W2-1c: 開いただけの w2-untouched.txt が変わった（sha: '$w2_untouched_sha1', mtime: '$w2_untouched_mtime1'）"
fi

# debug 前面に復帰してから確認・撮影する
a shell am start -W --activity-single-top -n "$COMP" >/dev/null
sleep 2
require_fg "w2-1 resume"
dump w2-1-resumed
shot w2-1-resumed

# ---- 17. W2-2 & W2-3: 複数ワープロタブ（非アクティブ含む）の保存と、2回目保存後の previous 1世代・隣に余計な物なし ----
# am start --activity-single-top は Android の Activity 遷移で onPause -> onNewIntent -> onResume を通るため、
# 編集より先に debug onNewIntent で 2 枚目のタブ w2-tab2.txt を追加しておき、編集後のタブ切り替えはタブ列タップで行う。
a shell am start -W --activity-single-top -n "$COMP" --es kiwa.debug.open "$DIR/w2-tab2.txt" >/dev/null
sleep 2
require_fg "w2-2 add w2-tab2.txt"
dump w2-2-tabs-ready
if ! tap_node 'text="w2-wp.txt"'; then
  fail "W2-2: タブ列の w2-wp.txt を押せなかった"
fi
sleep 1
tap_editor_start
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-2-tab1-edited
if [[ "$(body)" != "にほんごにほんごはるはあけぼの&#10;なつはよる&#10;" || "$(status)" != *"● 未保存"* ]]; then
  fail "W2-2: w2-wp.txt（2回目）の編集状態が期待と違う: $(body) / $(status)"
fi

# タブ列で 2 枚目のワープロタブ w2-tab2.txt へ切り替える（w2-wp.txt は非アクティブの未保存タブになる）
if ! tap_node 'text="w2-tab2.txt"'; then
  fail "W2-2: タブ列の w2-tab2.txt を押せなかった"
fi
sleep 1
dump w2-2-tab2-opened

# タブ切替時に非アクティブになった w2-wp.txt が予期せず保存されていないことを検査する
a pull "$DIR/w2-wp.txt" "$OUT/w2-wp.txt.on-tab-switch.pulled" >/dev/null 2>&1
if cmp -s "$OUT/w2-wp.txt.on-tab-switch.pulled" "$OUT/w2-wp.txt.expected1"; then
  pass "W2-2a: ワープロタブを編集して別タブへ切り替えただけでは保存されない"
else
  fail "W2-2a: タブ切替時に w2-wp.txt が予期せず書き換わった"
fi

# アクティブな w2-tab2.txt も編集する
tap_editor_start
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-2-both-dirty
shot w2-2-both-dirty

# HOME で非アクティブ（w2-wp.txt）とアクティブ（w2-tab2.txt）の両方が保存される
inp keyevent 3
sleep 2
a pull "$DIR/w2-wp.txt" "$OUT/w2-wp.txt.home2.pulled" >/dev/null 2>&1
a pull "$DIR/w2-tab2.txt" "$OUT/w2-tab2.txt.home2.pulled" >/dev/null 2>&1
if cmp -s "$OUT/w2-wp.txt.home2.pulled" "$OUT/w2-wp.txt.expected2" \
  && cmp -s "$OUT/w2-tab2.txt.home2.pulled" "$OUT/w2-tab2.txt.expected"; then
  pass "W2-2b: アクティブでないものを含む複数のワープロタブ（w2-wp.txt, w2-tab2.txt）が HOME で全部保存される"
else
  fail "W2-2b: 複数ワープロタブの HOME 保存結果が期待と違う"
fi

# W2-3: 2回目保存後の previous の中身が1回目保存後のバイトと cmp 一致、同じ元パスには1世代だけ、元ファイルの隣に余計な物が無い
wp_prev_hash="$(printf '%s' "$DIR/w2-wp.txt" | sha256sum | cut -d' ' -f1)"
wp_prev_rel="files/previous/${wp_prev_hash}.prev"
tab2_prev_hash="$(printf '%s' "$DIR/w2-tab2.txt" | sha256sum | cut -d' ' -f1)"
edit_prev_hash="$(printf '%s' "$DIR/edit.txt" | sha256sum | cut -d' ' -f1)"
a exec-out run-as "$PKG" cat "$wp_prev_rel" > "$OUT/w2-wp.txt.prev.pulled" 2>/dev/null || true
a shell "run-as $PKG ls -1 files/previous" 2>/dev/null | tr -d '\r' | sed '/^$/d' | sort > "$OUT/w2-prev-list-after2.txt"
comm -13 "$OUT/backup/prev-before.txt" "$OUT/w2-prev-list-after2.txt" > "$OUT/w2-prev-added-after2.txt"
printf '%s.prev\n%s.prev\n%s.prev\n' "$edit_prev_hash" "$wp_prev_hash" "$tab2_prev_hash" | sort > "$OUT/w2-prev-added-expected.txt"
if cmp -s "$OUT/w2-wp.txt.prev.pulled" "$OUT/w2-wp.txt.expected1" \
  && cmp -s "$OUT/w2-prev-added-after2.txt" "$OUT/w2-prev-added-expected.txt"; then
  pass "W2-3a: 2回目の保存で previous の中身が1回目保存後のバイトと一致し、同じ元パスには1世代だけ残る"
else
  fail "W2-3a: previous の中身または世代数が期待と違う（added: $(tr '\n' ' ' < "$OUT/w2-prev-added-after2.txt")）"
fi

a shell "ls -1A '$DIR'" | tr -d '\r' | sort > "$OUT/w2-dir-after2.txt"
if cmp -s "$OUT/w2-dir-before.txt" "$OUT/w2-dir-after2.txt"; then
  pass "W2-3b: 元ファイルの隣（$DIR）に余計な物が無い"
else
  fail "W2-3b: 元ファイルの隣（$DIR）のファイル一覧が変わった: $(comm -3 "$OUT/w2-dir-before.txt" "$OUT/w2-dir-after2.txt" | tr '\n' ' ')"
fi

# ---- 18. W2-4: 「前の版を開く」は期待の控え本文を無題のタブに表示し、元/控えのバイト/mtime を変えず書き戻さない ----
open_in_kiwa "$DIR/w2-wp.txt"
wp_mtime_before_prev="$(a shell "stat -c '%Y' '$DIR/w2-wp.txt'" | tr -d '\r')"
prev_mtime_before="$(a shell "run-as $PKG stat -c '%Y' '$wp_prev_rel'" | tr -d '\r')"
if menu_run "ファイル" "前の版を開く"; then
  dump w2-4-prev-opened
  shot w2-4-prev-opened
  if has_node 'text="（無題）"' \
    && has_node 'content-desc="モード ワープロ・横"' \
    && [[ "$(status)" != *"未保存"* ]] \
    && [[ "$(body)" == "にほんごはるはあけぼの&#10;なつはよる&#10;" ]]; then
    pass "W2-4a: 「前の版を開く」で期待の控え本文が無題のタブに表示される"
  else
    fail "W2-4a: 「前の版を開く」の表示が期待と違う: $(body) / $(status)"
  fi

  # 開いた無題タブを編集して HOME しても、元のファイルにも控え自身にも書き戻さないことを確かめる
  tap_editor_start
  inp text nihongo
  sleep 1
  inp keyevent 66
  sleep 1
  dump w2-4-prev-edited
  inp keyevent 3
  sleep 2
  a pull "$DIR/w2-wp.txt" "$OUT/w2-wp.txt.after-prev.pulled" >/dev/null 2>&1
  a exec-out run-as "$PKG" cat "$wp_prev_rel" > "$OUT/w2-wp.txt.prev-after-prev.pulled" 2>/dev/null || true
  wp_mtime_after_prev="$(a shell "stat -c '%Y' '$DIR/w2-wp.txt'" | tr -d '\r')"
  prev_mtime_after="$(a shell "run-as $PKG stat -c '%Y' '$wp_prev_rel'" | tr -d '\r')"
  if cmp -s "$OUT/w2-wp.txt.after-prev.pulled" "$OUT/w2-wp.txt.expected2" \
    && cmp -s "$OUT/w2-wp.txt.prev-after-prev.pulled" "$OUT/w2-wp.txt.expected1" \
    && [[ -n "$wp_mtime_before_prev" && "$wp_mtime_after_prev" == "$wp_mtime_before_prev" ]] \
    && [[ -n "$prev_mtime_before" && "$prev_mtime_after" == "$prev_mtime_before" ]]; then
    pass "W2-4b: 「前の版を開く」で元と控えのバイト・更新時刻が変わらず、元にも控え自身にも書き戻さない"
  else
    fail "W2-4b: 「前の版を開く」の後に元または控えが変わった（wp_mtime: '$wp_mtime_before_prev'->'$wp_mtime_after_prev', prev_mtime: '$prev_mtime_before'->'$prev_mtime_after'）"
  fi
else
  fail "W2-4: メニューから「前の版を開く」を押せなかった"
fi

# ---- 19. W2-5: HOME で保存・記録後 force-stop し、kiwa.debug.open 無しで起動すると最後のファイル・カーソル・モードが戻る ----
# 5a: ワープロ通常（w2-resume.txt の2行目先頭で編集 → 行 2, 桁 5・モード ワープロ）
open_in_kiwa "$DIR/w2-resume.txt"
tap_editor_start
inp keyevent 20   # 下矢印で行 2, 桁 1 へ
sleep 1
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-5a-before-home
if [[ "$(status)" != *"行 2, 桁 5 "* || "$(status)" != *"● 未保存"* ]]; then
  fail "W2-5a: HOME 前の位置・未保存状態が期待と違う: $(status)"
fi
inp keyevent 3
sleep 2
a pull "$DIR/w2-resume.txt" "$OUT/w2-resume.txt.pulled" >/dev/null 2>&1
a shell am force-stop "$PKG"
a shell am start -W -n "$COMP" >/dev/null
sleep 2
require_fg "w2-5a resume launch"
dump w2-5a-resumed
shot w2-5a-resumed
if cmp -s "$OUT/w2-resume.txt.pulled" "$OUT/w2-resume.txt.expected" \
  && has_node 'text="w2-resume.txt"' \
  && [[ "$(body)" == "いろはにほへと&#10;にほんごちりぬるを&#10;" ]] \
  && [[ "$(status)" == *"行 2, 桁 5 "* ]] \
  && has_node 'content-desc="モード ワープロ・横"'; then
  pass "W2-5a: HOME で保存・記録後に force-stop し kiwa.debug.open 無しで起動すると、最後のファイル・カーソル（行 2, 桁 5）・ワープロモードが戻る"
else
  fail "W2-5a: 続きからの復元（ワープロ通常）が期待と違う: $(status) / $(body)"
fi

# 5b: .txt をコードに切り替えて閉じたケース（行 1, 桁 3・モード コード）
if tap_node 'content-desc="モード ワープロ・横"'; then
  sleep 1
  tap_editor_start
  inp keyevent 22
  sleep 1
  inp keyevent 22
  sleep 1
  dump w2-5b-before-home
  if ! has_node 'content-desc="モード コード"' || [[ "$(status)" != *"行 1, 桁 3"* ]]; then
    fail "W2-5b: HOME 前のコードモード・カーソル位置が期待と違う: $(status)"
  fi
  inp keyevent 3
  sleep 2
  a shell am force-stop "$PKG"
  a shell am start -W -n "$COMP" >/dev/null
  sleep 2
  require_fg "w2-5b resume code launch"
  dump w2-5b-resumed
  shot w2-5b-resumed
  if has_node 'text="w2-resume.txt"' \
    && [[ "$(status)" == *"行 1, 桁 3"* ]] \
    && has_node 'content-desc="モード コード"'; then
    pass "W2-5b: .txt をコードに切り替えて HOME → force-stop → kiwa.debug.open 無しで起動すると、最後のファイル・カーソル（行 1, 桁 3）・コードモードが戻る"
  else
    fail "W2-5b: 続きからの復元（.txt をコードに切り替えたケース）が期待と違う: $(status)"
  fi
else
  fail "W2-5b: モード名の札を押してコードへ切り替えられなかった"
fi

# ---- 20. W2-6: autoSave OFF のコードタブを編集して HOME してもファイルは変わらない ----
open_in_kiwa "$DIR/w2-code.kt"
tap_editor_start
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-6-edited
shot w2-6-edited
w2_code_edited_status="$(status)"
w2_code_edited_body="$(body)"
inp keyevent 3
sleep 2
a pull "$DIR/w2-code.kt" "$OUT/w2-code.kt.pulled" >/dev/null 2>&1
w2_code_mtime_after="$(a shell "stat -c '%Y' '$DIR/w2-code.kt'" | tr -d '\r')"
a shell am start -W --activity-single-top -n "$COMP" >/dev/null
sleep 2
require_fg "w2-6 resume"
dump w2-6-resumed
shot w2-6-resumed
if [[ "$w2_code_edited_body" == "にほんごfun main()"* && "$w2_code_edited_status" == *"● 未保存"* ]] \
  && cmp -s "$OUT/w2-code.kt.pulled" "$OUT/w2-code.kt.sent" \
  && [[ -n "$w2_code_mtime0" && "$w2_code_mtime_after" == "$w2_code_mtime0" ]]; then
  pass "W2-6: autoSave OFF のコードタブ（w2-code.kt）を編集して HOME してもファイルのバイトと更新時刻は変わらない"
else
  fail "W2-6: autoSave OFF のコードタブで HOME 後にファイルが変わった、または編集できていなかった（mtime: '$w2_code_mtime0'->'$w2_code_mtime_after'）"
fi

# ---- 21. W2-7: Gboard で未確定の日本語がある状態から HOME した時の composing trace と保存実バイト ----
a shell "ls -1 '$TRACE_DIR' 2>/dev/null" | tr -d '\r' | sed '/^$/d' | sort > "$OUT/trace-before-w2-7.txt"
open_in_kiwa "$DIR/w2-composing.txt" trace
tap_editor_start
inp text nihongo
sleep 1
inp keyevent 66
sleep 1
dump w2-7-settled
inp text tesuto
sleep 1
dump w2-7-composing
shot w2-7-composing
if [[ "$(status)" != *"変換中"* || "$(body)" != "にほんごてすとあかさたな&#10;はまやらわ&#10;" ]]; then
  fail "W2-7: HOME 前に未確定の日本語（てすと）を作れなかった: $(body) / $(status)"
else
  inp keyevent 3
  sleep 2
  a pull "$DIR/w2-composing.txt" "$OUT/w2-composing.txt.pulled" >/dev/null 2>&1
  sleep 1
  w2_trace="$(a shell "ls -1 '$TRACE_DIR' 2>/dev/null" | tr -d '\r' | sed '/^$/d' | sort | comm -13 "$OUT/trace-before-w2-7.txt" - | tail -1)"
  [[ -n "$w2_trace" ]] && a pull "$TRACE_DIR/$w2_trace" "$OUT/trace-w2-composing.jsonl" >/dev/null 2>&1
  a shell am start -W --activity-single-top -n "$COMP" >/dev/null
  sleep 2
  require_fg "w2-7 resume"
  dump w2-7-resumed
  shot w2-7-resumed

  onpause_line="$(grep '"source":"app","event":"onPause"' "$OUT/trace-w2-composing.jsonl" 2>/dev/null | tail -1)"
  onpause_composing="$(sed -n 's/.*"composing":"\([^"]*\)".*/\1/p' <<<"$onpause_line")"
  pre_onpause_events="$(awk '/"event":"composing\.range","start":4,"end":7/{flag=1;next} /"source":"app","event":"onPause"/{flag=0} flag' "$OUT/trace-w2-composing.jsonl" 2>/dev/null || true)"
  gboard_pre_commit=0
  if grep -qE '"IC\.finishComposingText"|"IC\.commitText"' <<<"$pre_onpause_events"; then
    gboard_pre_commit=1
  fi
  if [[ -n "$onpause_composing" && "$onpause_composing" != "none" ]]; then
    composing_case="unconfirmed_remained_at_onPause(composing=$onpause_composing)"
  elif [[ "$onpause_composing" == "none" && $gboard_pre_commit -eq 1 ]]; then
    composing_case="gboard_committed_before_onPause(composing=none)"
  elif [[ "$onpause_composing" == "none" ]]; then
    composing_case="gboard_cleared_before_onPause(composing=none)"
  else
    composing_case="missing_onPause_trace"
  fi
  {
    printf 'trace_file=%s\n' "${w2_trace:-none}"
    printf 'onpause_line=%s\n' "${onpause_line:-none}"
    printf 'onpause_composing=%s\n' "${onpause_composing:-none}"
    printf 'gboard_pre_commit=%d\n' "$gboard_pre_commit"
    printf 'classification=%s\n' "$composing_case"
    printf 'saved_matches_settled=%s\n' "$(cmp -s "$OUT/w2-composing.txt.pulled" "$OUT/w2-composing.txt.expected-settled" && echo 1 || echo 0)"
    printf 'saved_contains_unconfirmed=%s\n' "$(grep -q 'てすと' "$OUT/w2-composing.txt.pulled" 2>/dev/null && echo 1 || echo 0)"
  } > "$OUT/w2-composing-report.txt"
  log "W2-7 記録: $composing_case / onPause.composing=${onpause_composing:-none}"

  # HOME より前に IME が確定した文字は既に確定済み。未確定のまま書いたケースとは分ける。
  if [[ "$onpause_composing" == "none" && $gboard_pre_commit -eq 1 ]] && cmp -s "$OUT/w2-composing.txt.pulled" "$OUT/w2-composing.txt.unsettled"; then
    pass "W2-7: Gboard が onPause より前に確定し、その確定済み本文が保存された（$composing_case）"
  elif grep -q 'てすと' "$OUT/w2-composing.txt.pulled" 2>/dev/null || cmp -s "$OUT/w2-composing.txt.pulled" "$OUT/w2-composing.txt.unsettled"; then
    fail "W2-7: 未確定の日本語（てすと）から HOME した時、未確定の中間文字列が保存された（要件違反: $composing_case）"
  elif cmp -s "$OUT/w2-composing.txt.pulled" "$OUT/w2-composing.txt.expected-settled" && [[ -n "$onpause_composing" ]]; then
    pass "W2-7: 未確定の日本語から HOME した時、未確定の中間文字列は保存されず確定済み本文だけが保存された（$composing_case）"
  else
    fail "W2-7: 未確定離席時の保存バイトまたは trace が期待と違う（$composing_case）"
  fi
fi

# Gboard の配列が開始時に「かな」以外だった場合は、edit.txt 上でキーボードを出して開始時の配列へ戻す
if [[ "$GBOARD_TOUCHED" -eq 1 && "$GBOARD_RESTORED" -ne 1 ]]; then
  open_in_kiwa "$DIR/edit.txt"
  tap_editor_start
  restore_gboard_mode || true
fi

finish
