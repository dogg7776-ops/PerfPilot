from pathlib import Path
import re, sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('buildsrc/PerfPilot')
java = root / 'app/src/main/java/com/oai/perfpilot'

# Version bump
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 18', s, count=1)
s = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.0.0-beta9-k90-gpufallback"', s, count=1)
gradle.write_text(s, encoding='utf-8')

runtime = java / 'RuntimeMetricsReader.kt'
s = runtime.read_text(encoding='utf-8')

# When frequency and load are hidden by HyperOS/SELinux, show a verified GPU identity instead of a meaningless N/A.
s = s.replace(
    '        readGpuUtilization()?.let { lastGpuSource = "ged-utilization"; return "$it% 负载" }\n        lastGpuSource = "unresolved"\n        return "N/A"\n    }',
    '        readGpuUtilization()?.let { lastGpuSource = "gpu-utilization"; return "$it% 负载" }\n        readGpuRenderer()?.let { lastGpuSource = "renderer"; return "$it\\n实时频率受限" }\n        lastGpuSource = "unresolved"\n        return "N/A"\n    }',
    1
)

# Replace beta8 utilization probe with broader MTK/Arm paths plus devfreq busy-time sampling.
pattern = r'    private fun readGpuUtilization\(\): Int\? \{.*?\n    \}\n\n    private fun readLegacyGpuProc\(\): String\? \{'
replacement = r'''    private fun readGpuUtilization(): Int? {
        val d = '$'
        val cmd = """
            for f in \
              /sys/module/ged/parameters/gpu_loading \
              /sys/kernel/ged/hal/gpu_utilization \
              /sys/kernel/ged/hal/gpu_loading \
              /sys/kernel/debug/ged/hal/gpu_utilization \
              /d/ged/hal/gpu_utilization; do
              [ -r "${d}f" ] || continue
              v=${d}(cat "${d}f" 2>/dev/null | head -n1)
              [ -n "${d}v" ] && { echo "DIRECT=${d}v"; exit 0; }
            done

            for f in /sys/devices/platform/soc/*/gpu_utilization /sys/devices/platform/*/gpu_utilization /sys/devices/platform/soc/*mali*/utilization /sys/devices/platform/*mali*/utilization; do
              [ -r "${d}f" ] || continue
              v=${d}(cat "${d}f" 2>/dev/null | head -n1)
              [ -n "${d}v" ] && { echo "DEVICE=${d}v"; exit 0; }
            done

            for x in /sys/class/devfreq/*; do
              [ -d "${d}x" ] || continue
              n=${d}(cat "${d}x/name" 2>/dev/null | head -n1); b=${d}(basename "${d}x")
              case "${d}n ${d}b" in *gpu*|*GPU*|*mali*|*MALI*|*mfg*|*MFG*|*13000000*|*13040000*) ;;
                *) continue;;
              esac
              for leaf in load gpu_load utilization gpu_utilization busy_percentage; do
                f="${d}x/${d}leaf"; [ -r "${d}f" ] || continue
                v=${d}(cat "${d}f" 2>/dev/null | head -n1)
                [ -n "${d}v" ] && { echo "DEVFREQ=${d}v"; exit 0; }
              done
              if [ -r "${d}x/busy_time" ] && [ -r "${d}x/total_time" ]; then
                b1=${d}(cat "${d}x/busy_time" 2>/dev/null); t1=${d}(cat "${d}x/total_time" 2>/dev/null)
                sleep 0.12
                b2=${d}(cat "${d}x/busy_time" 2>/dev/null); t2=${d}(cat "${d}x/total_time" 2>/dev/null)
                case "${d}b1:${d}t1:${d}b2:${d}t2" in *[!0-9:]*|'') ;;
                  *) db=${d}((b2-b1)); dt=${d}((t2-t1)); [ "${d}dt" -gt 0 ] && { u=${d}((100*db/dt)); echo "BUSY=${d}u"; exit 0; };;
                esac
              fi
            done
            exit 1
        """.trimIndent()
        val r = runRead(cmd, 2200)
        if (!r.ok || r.out.isBlank()) return null
        val text = r.out.trim()
        val preferred = Regex("(?i)(?:utilization|loading|load|busy)[^0-9]{0,20}([0-9]{1,3})")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (preferred != null && preferred in 0..100) return preferred
        return Regex("(^|[^0-9])([0-9]{1,3})(?=[^0-9]|$)")
            .findAll(text).mapNotNull { it.groupValues.getOrNull(2)?.toIntOrNull() }
            .firstOrNull { it in 0..100 }
    }

    private fun readGpuRenderer(): String? {
        val cmd = """
            line=$(dumpsys SurfaceFlinger 2>/dev/null | grep -im1 '^GLES:')
            if [ -n "$line" ]; then echo "$line"; exit 0; fi
            cmd gpu vkjson 2>/dev/null | grep -m1 -o '"deviceName"[^,}]*' && exit 0
            exit 1
        """.trimIndent()
        val r = runRead(cmd, 2200)
        if (!r.ok || r.out.isBlank()) return null
        val raw = r.out.trim().lineSequence().firstOrNull().orEmpty()
        if (raw.startsWith("GLES:", true)) {
            val parts = raw.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotBlank() }
            val renderer = parts.getOrNull(1) ?: parts.firstOrNull()
            if (!renderer.isNullOrBlank()) return renderer.take(34)
        }
        val vk = Regex("(?i)deviceName[^:]*[:=]\\s*\\\"?([^\\\",}]+)").find(raw)?.groupValues?.getOrNull(1)?.trim()
        return vk?.takeIf { it.isNotBlank() }?.take(34)
    }

    private fun readLegacyGpuProc(): String? {'''
ns, count = re.subn(pattern, replacement, s, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f'GPU utilization block replace failed: {count}')
s = ns

# Enrich the raw diagnostic probe with official/common MTK load sources and verified renderer identity.
needle = "            echo '[ged]'\n"
extra = "            echo '[renderer]'\n            dumpsys SurfaceFlinger 2>/dev/null | grep -im1 '^GLES:' || true\n            cmd gpu vkjson 2>/dev/null | grep -m1 -o '\\"deviceName\\"[^,}]*' || true\n            echo '[mtk-loading]'\n            for f in /sys/module/ged/parameters/gpu_loading /sys/kernel/ged/hal/gpu_utilization /sys/kernel/ged/hal/gpu_loading /sys/devices/platform/soc/*/gpu_utilization /sys/devices/platform/*/gpu_utilization; do [ -e \"${d}f\" ] && { echo \"PATH=${d}f\"; ls -l \"${d}f\" 2>/dev/null; cat \"${d}f\" 2>/dev/null | head -n2; }; done\n"
if needle not in s:
    raise SystemExit('gpuProbeReport marker missing')
s = s.replace(needle, extra + needle, 1)
runtime.write_text(s, encoding='utf-8')
print('patched beta9 GPU telemetry fallback')
