# STT Performance Diagnostics Guide

## Overview

The app collects detailed performance metrics during audio recording and speech-to-text processing. These metrics help identify device-specific scheduling or thermal issues that affect transcription accuracy and latency.

## How to Use

1. **Start a recording session** on your device
2. **Speak for at least 30-60 seconds** to collect meaningful data
3. **Stop the recording**
4. **Check logcat** for the diagnostic report (filter by tag: `AudioMetrics`)

## Reading the Report

The diagnostic report is printed to logcat when you stop recording. Here is what each section means:

### DEVICE INFO
```
Manufacturer: motorola
Model: motorola razr 70 plus
Android: 35
CPU Cores: 8
CPU Arch: arm64-v8a
```

**What to look for:**
- Device model and Android API level
- Number of CPU cores (affects threading strategy)
- CPU architecture (`arm64-v8a` is standard for modern devices)

---

### AUDIO CAPTURE
```
Total Reads: 523
Failed Reads: 0
Total Samples: 836800 (52.3s)
Read Latency: min=8ms avg=12.4ms max=45ms
WARNING: Audio read issues detected!
```

**What to look for:**
- **Failed Reads > 0**: Audio recording is failing intermittently
- **Max Read Latency > 150ms**: Audio reads are blocking too long (potential buffer underrun)
- **Avg Read Latency > 50ms**: Consistently slow audio capture

**Good values:**
- Failed Reads: 0
- Avg Read Latency: 10-30ms
- Max Read Latency: < 100ms

**Problematic values:**
- Failed Reads > 0 -> Audio driver/hardware issue
- Avg Read Latency > 50ms -> Device is too slow or busy
- Max Read Latency > 150ms -> CPU scheduling issues (big.LITTLE)

---

### BUFFER HEALTH
```
Channel Max Size: 15 chunks
Channel Timeouts: 0
WARNING: Buffer backlog detected - processing too slow!
```

**What to look for:**
- **Max Channel Size > 10**: Processing cannot keep up with audio capture
- **Channel Timeouts > 0**: Audio samples being dropped

**Good values:**
- Max Channel Size: 1-5 chunks
- Channel Timeouts: 0

**Problematic values:**
- Max Channel Size > 10 -> Inference too slow, audio being buffered
- Channel Timeouts > 0 -> Audio loss (degraded accuracy)

---

### VAD PROCESSING
```
Windows Processed: 1024
VAD Failures: 0
Speech Segments: 12
```

**What to look for:**
- **VAD Failures > 0**: Voice Activity Detection is failing
- **Speech Segments**: Number of times speech was detected (correlates with utterance count)

**Good values:**
- VAD Failures: 0
- Windows Processed: ~(recording_seconds * 31) -> 1 window every 32ms

**Problematic values:**
- VAD Failures > 0 -> Native library issue or corrupted audio
- Windows Processed much lower than expected -> Audio capture failing silently

---

### INFERENCE
```
Total Calls: 245
Success: 243
Failures: 2
Inference Time: min=45ms avg=87.3ms max=340ms
WARNING: Inference performance issues detected!
```

**What to look for:**
- **Failures > 0**: ASR inference is failing
- **Avg Inference Time > 150ms**: Model processing is too slow
- **Max Inference Time > 500ms**: CPU throttling or thread starvation

**Good values:**
- Failures: 0
- Avg Inference Time: 50-150ms
- Max Inference Time: < 300ms

**Problematic values:**
- Failures > 0 -> Model corruption or memory pressure
- Avg Inference Time > 200ms -> Running on efficiency cores
- Max Inference Time > 500ms -> Thermal throttling or thread scheduling issues

---

### THREAD INFO
```
Recording Thread: DefaultDispatcher-worker-2 (ID: 12345)
Processing Thread: DefaultDispatcher-worker-3 (ID: 12346)
```

**What this tells you:**
- Both threads use `DefaultDispatcher` and share CPU time
- Thread IDs help identify if threads are being recreated
- Widely diverging thread IDs indicate high thread pool contention

---

### HEALTH ASSESSMENT
```
Issues detected in: Audio capture, Inference performance
```

**Summary of detected issues:**
- **No issues detected** = Everything is working within latency targets
- **Audio capture** = Failed reads or high latency
- **Buffer backlog** = Processing too slow
- **Inference performance** = Slow or failing ASR
- **VAD failures** = Voice detection errors

---

## Common Issues and Causes

### Issue 1: Buffer Backlog + Slow Inference
**Symptoms:**
- Max Channel Size > 10
- Avg Inference Time > 150ms

**Likely Cause:**
- Threads scheduled on efficiency cores (little cores)
- Insufficient thread count allocated

**Solution:**
- Adjust `numThreads` based on device performance core count
- Pin inference threads to big/prime cores

---

### Issue 2: High Audio Read Latency
**Symptoms:**
- Max Read Latency > 150ms
- Sporadic failed reads

**Likely Cause:**
- Android scheduler placing recording thread on a busy efficiency core
- Device under heavy memory or I/O load

**Solution:**
- Increase audio buffer size (from `numBytes * 2` to `numBytes * 4`)
- Use `SCHED_FIFO` priority for the recording thread
- Switch audio source from `MIC` to `VOICE_RECOGNITION`

---

### Issue 3: Inference Spikes (High Max, Normal Avg)
**Symptoms:**
- Avg Inference Time: 80ms (good)
- Max Inference Time: 450ms (bad)

**Likely Cause:**
- Thread migration between performance and efficiency cores mid-inference
- Thermal throttling when folded
- Concurrent model load or background processes competing for memory bandwidth

**Solution:**
- Pin inference threads to performance cores
- Reduce thread count to prevent over-subscription
- Pace background model loads (`ModelLoadPacing`) while recording is active

---

### Issue 4: Audio Sample Loss
**Symptoms:**
- Audio drop rate > 1%
- Total Samples much less than expected

**Likely Cause:**
- Audio buffer too small for device latency
- Recording thread not scheduled in time

**Solution:**
- Increase audio buffer multiplier
- Increase recording interval from 100ms to 150ms
- Use `SCHED_FIFO` for the recording thread

---

## Comparing Device Profiles

When comparing unfolded vs. folded thermal states or different SoC configurations:

| Metric | Razr 70 Plus Unfolded (Nominal) | Razr 70 Plus Folded (Throttled) | Cause |
|--------|---------------------------------|---------------------------------|-------|
| Avg Read Latency | 12ms | 28ms | Reduced clock ceiling while folded |
| Max Channel Size | 2 | 14 | Inference backlog under thermal cap |
| Avg Inference | 78ms | 175ms | Cortex-A720 downclocking |
| Max Inference | 145ms | 460ms | Core migration to Cortex-A520 |

---

## Advanced Analysis

### CPU Core Detection (in logs)

Look for this pattern in `/proc/cpuinfo` diagnostics:
```
processor : 0
CPU part : 0xd80 (Cortex-A520 - efficiency)
...
processor : 4
CPU part : 0xd81 (Cortex-A720 - performance)
...
processor : 7
CPU part : 0xd82 (Cortex-X4 - prime)
```

On the Snapdragon 8s Gen 3 (`SM8635` in the Motorola razr 70 plus):
- **Prime / Performance cluster**: 1x Cortex-X4 (3.0 GHz) + 4x Cortex-A720 (2.8 GHz)
- **Efficiency cluster**: 3x Cortex-A520 (2.0 GHz)

---

## Next Steps

Once you have identified issues from the metrics:

1. **For audio capture issues** -> Check `AudioSource` and buffer multiplier
2. **For inference issues** -> Adjust `numThreads` and core affinity
3. **For buffer backlog** -> Check `ModelLoadPacing` and concurrent I/O
4. **For device-specific issues** -> Review device thermal and memory thresholds

See the implementation in:
- `AudioMetrics.kt` - Metrics collection
- `SherpaOnnxDataSource.kt` - Recording pipeline
- `AppModule.kt` - STT configuration
