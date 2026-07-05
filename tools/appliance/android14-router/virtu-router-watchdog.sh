#!/system/bin/sh
# VirtuVPN Router Android 14 appliance watchdog.
# Runs outside the app process through Magisk service.d.

PKG="com.virtuvpn.android"
PREFS="/data/data/$PKG/shared_prefs/virtuvpn_router.xml"
DEX="/data/adb/virtu-tether.dex"
LOG="/data/local/tmp/virtu-router-watchdog.log"
PIDFILE="/dev/virtu-router-watchdog.pid"
BLOCK_RULE_PRIORITY=20901
BLOCK_ROUTE_TABLE=1048
FAST_ITERS=120
INTERVAL=30

IP="/system/bin/ip"
IPTABLES="/system/bin/iptables"

if [ -f "$PIDFILE" ]; then
  old_pid="$(cat "$PIDFILE" 2>/dev/null)"
  if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null &&
     grep -qa virtu-router-watchdog "/proc/$old_pid/cmdline" 2>/dev/null; then
    exit 0
  fi
fi
echo $$ > "$PIDFILE"

log() {
  ts="$(date "+%Y-%m-%d %H:%M:%S" 2>/dev/null)"
  echo "$ts $*" >> "$LOG"
}

resolve_uid() {
  uid="$(stat -c %u "/data/data/$PKG" 2>/dev/null)"
  if [ -n "$uid" ]; then echo "$uid"; return 0; fi
  cmd package list packages -U "$PKG" 2>/dev/null | sed -n 's/.*uid://p' | head -n 1
}

pref_bool_true() {
  key="$1"
  grep -q "name=\"$key\" value=\"true\"" "$PREFS" 2>/dev/null
}

pref_bool_false() {
  key="$1"
  grep -q "name=\"$key\" value=\"false\"" "$PREFS" 2>/dev/null
}

pref_string() {
  key="$1"
  sed -n "s/.*<string name=\"$key\">\\(.*\\)<\\/string>.*/\\1/p" "$PREFS" 2>/dev/null | head -n 1
}

router_desired() {
  [ -f "$PREFS" ] || return 1
  pref_bool_true router_desired_active && return 0
  pref_bool_false router_desired_active && return 1
  grep -q 'name="last_active_router_tunnel"' "$PREFS" 2>/dev/null && return 0
  return 1
}

tether_ifaces() {
  recorded="$(pref_string last_active_router_tethers | tr ',' ' ')"
  if [ -n "$recorded" ]; then
    echo "$recorded"
  else
    echo "swlan0 ap0 wlan1 softap0"
  fi
}

rules_installed() {
  $IPTABLES -w 3 -S 2>/dev/null | grep -q "VIRTUVPN_ROUTER_FWD -i"
}

pre_block() {
  if ! $IP route show table "$BLOCK_ROUTE_TABLE" 2>/dev/null | grep -q "unreachable default"; then
    err="$($IP route replace unreachable default table "$BLOCK_ROUTE_TABLE" 2>&1)"
    [ -n "$err" ] && log "pre_block route error: $err"
  fi
  for ifc in $(tether_ifaces); do
    $IP rule show 2>/dev/null | grep -q "iif $ifc .*lookup $BLOCK_ROUTE_TABLE" && continue
    err="$($IP rule add pref "$BLOCK_RULE_PRIORITY" iif "$ifc" lookup "$BLOCK_ROUTE_TABLE" 2>&1)"
    [ -n "$err" ] && log "pre_block rule($ifc) error: $err"
  done
}

block_armed() {
  $IP route show table "$BLOCK_ROUTE_TABLE" 2>/dev/null | grep -q "unreachable default" || return 1
  for ifc in $(tether_ifaces); do
    $IP rule show 2>/dev/null | grep -q "iif $ifc .*lookup $BLOCK_ROUTE_TABLE" && return 0
  done
  return 1
}

assert_always_on() {
  want="$(settings get secure virtu_router_always_on_pkg 2>/dev/null)"
  [ "$want" = "null" ] && want=""
  [ -n "$want" ] || return 0
  cur="$(settings get secure always_on_vpn_app 2>/dev/null)"
  if [ "$cur" != "$want" ]; then
    settings put secure always_on_vpn_app "$want" >/dev/null 2>&1 &&
      log "always-on VPN set -> $want (was $cur)"
  fi
}

tether_up() {
  for ifc in $(tether_ifaces); do
    $IP -o link show "$ifc" 2>/dev/null | grep -q "state UP" && return 0
    $IP -4 -o addr show "$ifc" 2>/dev/null | grep -q "inet " && return 0
  done
  return 1
}

start_tether() {
  [ -f "$DEX" ] || { log "WARN tether dex missing: $DEX"; return 1; }
  out="$(CLASSPATH=$DEX app_process /system/bin TetherStarter start 2>&1)"
  log "tether start: $out"
  case "$out" in *TETHERING_STARTED*) return 0;; *) return 1;; esac
}

app_running() {
  pidof "$PKG" >/dev/null 2>&1
}

kick_app() {
  am start-foreground-service -n "$PKG/com.wireguard.android.VpnRouterService" >/dev/null 2>&1 ||
    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
}

put_if_changed() {
  namespace="$1"; key="$2"; wanted="$3"
  current="$(settings get "$namespace" "$key" 2>/dev/null)"
  if [ "$current" != "$wanted" ]; then
    settings put "$namespace" "$key" "$wanted" >/dev/null 2>&1
    after="$(settings get "$namespace" "$key" 2>/dev/null)"
    if [ "$after" = "$wanted" ]; then log "set $namespace/$key $current -> $wanted"
    else log "WARN failed to set $namespace/$key -> $wanted, after=$after"; fi
  fi
}

appop_allow() {
  cmd appops set "$PKG" "$1" allow >/dev/null 2>&1 || log "WARN appops set failed: $1"
}

apply_os_hardening() {
  put_if_changed secure wifi_ap_timeout_setting 0
  put_if_changed global tether_offload_disabled 1
  put_if_changed global mobile_data 1
  put_if_changed global airplane_mode_on 0
  cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
  appop_allow RUN_IN_BACKGROUND
  appop_allow RUN_ANY_IN_BACKGROUND
  appop_allow START_FOREGROUND
  appop_allow ACTIVATE_VPN
  appop_allow ESTABLISH_VPN_SERVICE
  appop_allow SYSTEM_ALERT_WINDOW
  appop_allow REQUEST_INSTALL_PACKAGES
  uid="$(resolve_uid)"
  if [ -n "$uid" ]; then
    magisk --sqlite "UPDATE policies SET policy=2, until=0, logging=1, notification=1 WHERE uid=$uid;" >/dev/null 2>&1 || true
  else
    log "WARN unable to resolve $PKG uid"
  fi
}

tick() {
  if router_desired; then
    pre_block
    if block_armed; then
      assert_always_on
      if ! rules_installed; then
        tether_up || start_tether
        app_running || kick_app
      fi
    else
      log "pre_block not armed yet; withholding hotspot start"
    fi
  fi
  apply_os_hardening
}

log "watchdog start (pid $$)"
i=0
while [ "$i" -lt "$FAST_ITERS" ]; do
  i=$((i+1))
  tick
  if router_desired && rules_installed; then
    log "router protected; entering steady interval"
    break
  fi
  sleep 1
done
while true; do
  tick
  sleep "$INTERVAL"
done

