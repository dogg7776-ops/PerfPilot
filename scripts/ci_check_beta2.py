from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("buildsrc/PerfPilot")
checks = []

def require(path, text, label):
    data = (root / path).read_text(encoding="utf-8")
    checks.append((label, text in data))

require("app/src/main/AndroidManifest.xml", "rikka.shizuku.ShizukuProvider", "Shizuku provider declared")
require("app/src/main/java/com/oai/perfpilot/DeviceProfile.kt", "MT6993", "K90 Max / MT6993 profile")
require("app/src/main/java/com/oai/perfpilot/DeviceProfile.kt", "165", "K90 Max 165 Hz profile")
require("app/src/main/java/com/oai/perfpilot/PerfController.kt", "回读验证成功", "sysfs write verification")
require("app/src/main/java/com/oai/perfpilot/PerfManagerClient.kt", "perfmanager", "MTK perfmanager client")
require("app/src/main/java/com/oai/perfpilot/DeviceProfile.kt", "/sys/kernel/fpsgo", "FPSGO resolver roots")
require("app/src/main/java/com/oai/perfpilot/DeviceProfile.kt", "/sys/kernel/ged", "GED resolver roots")
require("app/build.gradle.kts", 'versionName = "1.0.0-beta4-k90-runtimefix"', "beta4 runtime fix version")
require("app/src/main/java/com/oai/perfpilot/DiagnosticsActivity.kt", "showDiagnosticError", "diagnostics exception guard")
require("app/src/main/java/com/oai/perfpilot/AdvancedTuningActivity.kt", "showFallback", "advanced page startup guard")
require("app/src/main/java/com/oai/perfpilot/ParameterActivity.kt", "读取异常", "parameter read guard")
require("app/src/main/java/com/oai/perfpilot/UiKit.kt", "decorView.post", "HyperOS deferred insets setup")
require("app/src/main/java/com/oai/perfpilot/UiKit.kt", "Window styling must never prevent", "window styling crash guard")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "sampleProcStat", "independent CPU telemetry")
require("app/src/main/java/com/oai/perfpilot/RuntimeMetricsReader.kt", "SurfaceFlinger --latency", "SurfaceFlinger FPS probe")
require("app/src/main/java/com/oai/perfpilot/MainActivity.kt", "已授权，但执行通道异常", "Shizuku execution health surfaced")

source = "\n".join(
    p.read_text(encoding="utf-8", errors="ignore")
    for p in (root / "app/src/main").rglob("*") if p.is_file()
).lower()
for forbidden in ["stop thermald", "stop vendor.thermal", "thermalservice stop", "trip_point_temp"]:
    checks.append((f"dangerous thermal mutation absent: {forbidden}", forbidden not in source))

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("OK   " if ok else "FAIL ") + name)
if failed:
    print(f"{len(failed)} checks failed", file=sys.stderr)
    raise SystemExit(1)
print(f"{len(checks)} static checks passed")
