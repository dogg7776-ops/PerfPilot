from pathlib import Path
import shutil

repo = Path(__file__).resolve().parents[1]
root = repo / "buildsrc" / "PerfPilot"
java = root / "app/src/main/java/com/oai/perfpilot"
overlay = repo / "overlays/beta2"

java_files = [
    "DeviceProfile.kt",
    "KernelNodeResolver.kt",
    "PerfManagerClient.kt",
    "PerfController.kt",
    "PerfCatalog.kt",
    "DiagnosticsActivity.kt",
    "ParameterActivity.kt",
    "PrivilegedShellService.kt",
    "ShellEngine.kt",
    "MainActivity.kt",
    "GameProfilesActivity.kt",
    "AdvancedTuningActivity.kt",
    "UiKit.kt",
]

for name in java_files:
    src = overlay / name
    if not src.is_file():
        raise SystemExit(f"missing beta2 overlay: {src}")
    shutil.copy2(src, java / name)

shutil.copy2(overlay / "app-build.gradle.kts", root / "app/build.gradle.kts")
shutil.copy2(overlay / "AndroidManifest.xml", root / "app/src/main/AndroidManifest.xml")
shutil.copy2(overlay / "styles.xml", root / "app/src/main/res/values/styles.xml")

print("Applied PerfPilot 1.0 beta2 K90 Max full overlay onto trusted v0.3 source")
