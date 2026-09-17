from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("buildsrc/PerfPilot")
p = root / "app/src/main/java/com/oai/perfpilot/PerfController.kt"
s = p.read_text(encoding="utf-8")

# Make every existing caller (including game profiles) use the verified display controller.
s = s.replace(
    "fun supportedRefreshRates(): List<Int> = profile.refreshRates",
    "fun supportedRefreshRates(): List<Int> = DisplayController(context, shell).supportedRates().ifEmpty { profile.refreshRates }"
)

replacement = '''fun setRefreshRate(hz: Int): String {
        if (hz <= 0) return "刷新率：保持当前设置"
        val r = DisplayController(context, shell).apply(hz)
        return (if (r.ok) "刷新率策略已提交并完成回读\\n" else "刷新率切换未验证成功\\n") + r.detail
    }

    fun setAnimationScale'''
pattern = re.compile(r'fun setRefreshRate\(hz: Int\): String \{.*?\n    fun setAnimationScale', re.S)
if not pattern.search(s):
    raise SystemExit("failed to patch PerfController.setRefreshRate")
# Use a function replacement so Python's regex engine does not interpret \\n in the Kotlin string literal.
s = pattern.sub(lambda _: replacement, s, count=1)

s = s.replace(
    "val targetHz = if (165 in profile.refreshRates) 165 else profile.refreshRates.maxOrNull() ?: 120",
    "val targetHz = DisplayController(context, shell).supportedRates().maxOrNull() ?: profile.refreshRates.maxOrNull() ?: 120"
)

p.write_text(s, encoding="utf-8")
print("Patched PerfController refresh-rate callers to verified DisplayController")
