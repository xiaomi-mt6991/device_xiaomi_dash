#!/vendor/bin/sh

# Keep the mtu3 USB controller's wakeup source from wedging system suspend.
#
# Symptom
# -------
# 16701000.usb0 acquires a wakeup source and leaves it active.
# /sys/power/wakeup_count then never settles, so pm_get_wakeup_count()
# blocks inside SystemSuspend and the device issues no suspends at all.
# Measured on dash: suspend_stats/success frozen at 0 with
# 16701000.usb0/power/wakeup_active reading 1; a single disable->enable
# toggle dropped it to 0 and the same session then reached 286
# successful suspends.
#
# Two distinct triggers, both observed
# ------------------------------------
#  1. Boot gadget bring-up (~t+7s). Covered by the toggle at
#     sys.boot_completed.
#  2. Attaching a USB charger. PowerDet takes a "charger suspend
#     wakelock" on the same device and re-arms the source, so a boot-time
#     toggle is long since stale by the time a cable is plugged in.
#
# Why wakeup_active instead of /sys/power/wakeup_count
# ----------------------------------------------------
# An earlier revision probed /sys/power/wakeup_count, which is the exact
# condition SystemSuspend blocks on -- but that read is itself
# uninterruptible once the wedge exists (pm_get_wakeup_count() waits in
# TASK_UNINTERRUPTIBLE), so the probe wedged on the very thing it watches
# for. timeout cannot rescue it: toybox timeout reaps only children that
# actually die, and a task stuck in D state does not, so the loop stalls
# regardless of -k. Measured on dash the same read took 0.6-2.1s while
# healthy, so a short timeout also misfired under load.
#
# power/wakeup_active is an atomic_read in the driver, so it can never
# block, and it sits on the one node this guard exists to manage. The
# trade-off is that a wedge on some *other* wakeup node is not detected
# here; /sys/kernel/debug/wakeup_sources on dash showed no other
# persistently active source, so that risk is accepted rather than
# guessed at.
#
# Cost when healthy: one non-blocking sysfs read and one sleep per
# interval, no forks. The shell holds no wakelock, so it does not itself
# keep the device awake -- when the device suspends the loop pauses and
# resumes with it.

USB0_POWER=/sys/devices/platform/soc/16701000.usb0/power
USB0_WAKEUP=$USB0_POWER/wakeup
USB0_ACTIVE=$USB0_POWER/wakeup_active
POLL_SECONDS=30

release_wakeup_source() {
    # disable->enable re-creates the wakeup source in its inactive state.
    # Verified durable: after a manual toggle the source stayed clean for
    # 15+ minutes of continued charging, and for a full session of
    # charging plus adb.
    echo disabled > "$USB0_WAKEUP"
    echo enabled > "$USB0_WAKEUP"
}

# Clear the boot-time gadget leak immediately rather than waiting a full
# poll interval for the first check.
[ -e "$USB0_WAKEUP" ] && release_wakeup_source

while true; do
    # mksh builtin read: no fork, no exec, and nothing that can end up
    # waiting on a D-state child. A failure (node absent, EACCES) leaves
    # ACTIVE empty, so the next iteration simply retries.
    active=
    read -r active < "$USB0_ACTIVE"
    [ "$active" = "1" ] && release_wakeup_source
    /vendor/bin/sleep "$POLL_SECONDS"
done
