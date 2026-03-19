package org.dynamisengine.audio.dsp.device;

import org.dynamisengine.audio.api.device.AudioFormat;

/**
 * Immutable snapshot of audio subsystem telemetry.
 *
 * Captured by {@link AudioDeviceManager#captureTelemetry()} at a point in time.
 * Safe to read from any thread after capture. No allocation on the capture path
 * beyond this record itself (acceptable — telemetry is not on the RT hot path).
 *
 * @param state              current lifecycle state
 * @param backendName        active backend name (e.g., "CoreAudio")
 * @param deviceDescription  active device description string
 * @param negotiatedFormat   negotiated audio format (null if no device)
 * @param outputLatencyMs    driver-reported output latency in ms
 * @param swapGeneration     monotonic swap counter
 * @param callbackCount      total platform callback invocations
 * @param feederBlockCount   total DSP blocks rendered
 * @param feederAvgNanos     average render time per block (ns)
 * @param feederMaxNanos     worst-case render time (ns)
 * @param startupToFirstAudioMs  time from start() to first block (ms), -1 if not measured
 * @param ringAvailable      current ring occupancy (blocks)
 * @param ringSlotCount      ring capacity (blocks)
 * @param ringHighWatermark  peak ring occupancy ever observed
 * @param ringLowWatermark   minimum ring occupancy ever observed
 * @param ringUnderruns      total underrun events
 * @param ringOverruns       total overrun events
 */
public record AudioTelemetry(
        AudioDeviceManager.State state,
        String backendName,
        String deviceDescription,
        AudioFormat negotiatedFormat,
        float outputLatencyMs,
        long swapGeneration,
        long callbackCount,
        long feederBlockCount,
        long feederAvgNanos,
        long feederMaxNanos,
        float startupToFirstAudioMs,
        int ringAvailable,
        int ringSlotCount,
        int ringHighWatermark,
        int ringLowWatermark,
        long ringUnderruns,
        long ringOverruns) {

    /**
     * Callback cadence in Hz (callbacks per second).
     * Returns 0 if no format or no callbacks yet.
     */
    public double callbackRateHz() {
        if (negotiatedFormat == null || negotiatedFormat.blockSize() == 0) return 0;
        // Each callback delivers blockSize frames at sampleRate Hz
        // Actual rate = callbackCount / elapsed, but we estimate from format
        return (double) negotiatedFormat.sampleRate() / negotiatedFormat.blockSize();
    }

    /**
     * DSP budget utilization as a percentage (0-100+).
     * Compares average render time against the block duration.
     * &gt;100% means the DSP is slower than real-time.
     */
    public double dspBudgetPercent() {
        if (negotiatedFormat == null || negotiatedFormat.sampleRate() == 0) return 0;
        double blockDurationNanos = (double) negotiatedFormat.blockSize()
                / negotiatedFormat.sampleRate() * 1_000_000_000.0;
        if (blockDurationNanos == 0) return 0;
        return feederAvgNanos / blockDurationNanos * 100.0;
    }

    /**
     * Ring buffer fill percentage (0-100).
     */
    public double ringFillPercent() {
        return ringSlotCount > 0 ? (double) ringAvailable / ringSlotCount * 100.0 : 0;
    }

    /**
     * Returns a compact human-readable status line suitable for a profiler overlay.
     */
    public String statusLine() {
        return String.format("%s | %s | %s | %dHz %dch | DSP %.1f%% | Ring %d/%d | U:%d O:%d | Swap:%d",
                state,
                backendName != null ? backendName : "?",
                deviceDescription != null ? truncate(deviceDescription, 30) : "?",
                negotiatedFormat != null ? negotiatedFormat.sampleRate() : 0,
                negotiatedFormat != null ? negotiatedFormat.channels() : 0,
                dspBudgetPercent(),
                ringAvailable, ringSlotCount,
                ringUnderruns, ringOverruns,
                swapGeneration);
    }

    /**
     * Returns a detailed multi-line telemetry report.
     */
    public String detailedReport() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== Audio Telemetry ===\n");
        sb.append("State:             ").append(state).append('\n');
        sb.append("Backend:           ").append(backendName).append('\n');
        sb.append("Device:            ").append(deviceDescription).append('\n');
        if (negotiatedFormat != null) {
            sb.append("Format:            ").append(negotiatedFormat.sampleRate()).append("Hz, ")
              .append(negotiatedFormat.channels()).append("ch, ")
              .append(negotiatedFormat.blockSize()).append(" frames\n");
        }
        sb.append("Output latency:    ").append(String.format("%.2f ms", outputLatencyMs)).append('\n');
        sb.append("Swap generation:   ").append(swapGeneration).append('\n');
        sb.append("Callback count:    ").append(callbackCount).append('\n');
        sb.append("Callback rate:     ").append(String.format("%.1f Hz", callbackRateHz())).append('\n');
        sb.append("Feeder blocks:     ").append(feederBlockCount).append('\n');
        sb.append("Feeder avg:        ").append(String.format("%.3f ms", feederAvgNanos / 1_000_000.0)).append('\n');
        sb.append("Feeder max:        ").append(String.format("%.3f ms", feederMaxNanos / 1_000_000.0)).append('\n');
        sb.append("DSP budget:        ").append(String.format("%.1f%%", dspBudgetPercent())).append('\n');
        if (startupToFirstAudioMs >= 0) {
            sb.append("Startup→audio:     ").append(String.format("%.2f ms", startupToFirstAudioMs)).append('\n');
        }
        sb.append("Ring occupancy:    ").append(ringAvailable).append(" / ").append(ringSlotCount)
          .append(" (").append(String.format("%.0f%%", ringFillPercent())).append(")\n");
        sb.append("Ring watermarks:   lo=").append(ringLowWatermark)
          .append(" hi=").append(ringHighWatermark).append('\n');
        sb.append("Ring underruns:    ").append(ringUnderruns).append('\n');
        sb.append("Ring overruns:     ").append(ringOverruns).append('\n');
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
