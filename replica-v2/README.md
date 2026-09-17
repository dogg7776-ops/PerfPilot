# PerfPilot 2.0 alpha1

A clean-room reimplementation aimed at Xiaomi HyperOS performance tuning through **Shizuku/ADB shell (UID 2000)** rather than requiring root.

Implemented in this alpha:
- Shizuku UserService execution layer and remote UID verification.
- MediaTek FPSGO read/write with post-write readback verification.
- MediaTek CFP read/write.
- GED DVFS / DCS discovery and DCS switching.
- HyperOS refresh-rate settings.
- Joyose / PowerKeeper cloud-control component Binder calls based on commands recovered from the reference APK.
- Xiaomi MiCharge `smart_chg` Binder call.
- Haptic properties recovered from the reference APK (without automatic zygote restart).
- SurfaceFlinger latency, thermal-zone and uclamp monitoring.
- Shizuku shell command laboratory.

Intentionally not exposed as a one-tap action:
- The reference APK's script that rewrites thermal trip points to 135 C. PerfPilot only reads thermal state for now.

Every low-level sysfs write uses an immediate readback where practical, so unsupported/denied writes are visible instead of being reported as fake success.
