from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("buildsrc/PerfPilot")
checks = []

def require(path, text, label):
    data = (root / path).read_text(encoding="utf-8")
    checks.append((label, text in data))

require("app/src/main/AndroidManifest.xml", "rikka.shizuku.ShizukuProvider", "Shizuku provider declared")
require("app/src/main/AndroidManifest.xml", ".SystemToolsActivity", "system tools activity declared")
require("app/src/main/java/com/oai/perfpilot/DeviceProfile.kt", "MT6993", "K90 Max / MT6993 profile")
require("app/src/main/java/com/oai/perfpilot/PerfManagerClient.kt", "service call perfmanager 1", "MTK perfmanager acquire route")
require("app/src/main/java/com/oai/perfpilot/PerfManagerClient.kt", "service call perfmanager 3", "MTK perfmanager release route")
require("app/src/main/java/com/oai/perfpilot/PerfController.kt", "回读验证成功", "sysfs write verification")
require("app/src/main/java/com/oai/perfpilot/PerfController.kt", "DisplayController(context, shell).apply", "all refresh callers use verified display controller")
require("app/src/main/java/com/oai/perfpilot/DisplayController.kt", "set-user-preferred-display-mode", "DisplayManager refresh route")
require("app/src/main/java/com/oai/perfpilot/DisplayController.kt", "supportedModes", "physical display mode discovery")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "/sys/kernel/debug/ged/hal/current_freqency", "GED debugfs GPU frequency")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "/d/ged/hal/current_freqency", "legacy GED GPU frequency")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "stack_working_opp_table", "MTK gpufreqv2 OPP mapping")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "/sys/module/ged/parameters/gpu_loading", "verified GPU utilization fallback")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "EXTRA_TEMPERATURE", "battery temperature source")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "readForegroundFps", "foreground SurfaceFlinger FPS")
require("app/src/main/java/com/oai/perfpilot/OverlayService.kt", "ACTION_STOP", "overlay explicit stop action")
require("app/src/main/java/com/oai/perfpilot/OverlayService.kt", "关闭悬浮窗", "overlay notification stop action")
require("app/src/main/java/com/oai/perfpilot/MainActivity.kt", "OverlayService.stop(this)", "home overlay start/stop toggle")
require("app/src/main/java/com/oai/perfpilot/SystemToolsController.kt", "MILLET_NO_RESTRICT_APP", "reversible background protection")
require("app/src/main/java/com/oai/perfpilot/SystemToolsController.kt", "pm disable-user", "explicit Xiaomi cloud-control component route")
require("app/src/main/java/com/oai/perfpilot/AdvancedTuningActivity.kt", "PerfCatalog.categories", "all performance categories surfaced")
require("app/src/main/java/com/oai/perfpilot/PerfCatalog.kt", "触控 / 启动", "touch/launch resources catalogued")
require("app/src/main/java/com/oai/perfpilot/PerfCatalog.kt", '"C2PS"', "C2PS resources catalogued")
require("app/src/main/java/com/oai/perfpilot/PerfCatalog.kt", "热感知（只读）", "thermal-aware resources read-only")
require("app/build.gradle.kts", 'versionName = "2.0.0-alpha1-original-compatible"', "v2 original-compatible version")
require("app/src/main/java/com/oai/perfpilot/UiKit.kt", "Window styling must never prevent", "window styling crash guard")

source = "\n".join(
    p.read_text(encoding="utf-8", errors="ignore")
    for p in (root / "app/src/main").rglob("*") if p.is_file()
).lower()
for forbidden in [
    "stop thermald",
    "stop vendor.thermal",
    "thermalservice stop",
    "trip_point_temp",
    "echo 135000",
]:
    checks.append((f"dangerous thermal mutation absent: {forbidden}", forbidden not in source))

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("OK   " if ok else "FAIL ") + name)
if failed:
    print(f"{len(failed)} checks failed: {failed}", file=sys.stderr)
    raise SystemExit(1)
print(f"{len(checks)} static checks passed")
