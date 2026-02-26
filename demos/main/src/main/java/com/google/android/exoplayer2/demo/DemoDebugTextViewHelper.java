/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.demo;

import android.media.MediaDrm;
import android.net.TrafficStats;
import android.os.Debug;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.util.DebugTextViewHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Extends DebugTextViewHelper to prepend live FPS, CPU, memory, and network metrics. */
/* package */ final class DemoDebugTextViewHelper extends DebugTextViewHelper {

  private static final String LOG_TAG = "ExoDemo";
  private static final int LOG_INTERVAL_SAMPLES = 10;

  private final ExoPlayer player;
  private long lastRenderedFrameCount;
  private long lastUpdateTimeMs;
  private long lastCpuTimeMs;
  private long lastRxBytes;
  private long lastTxBytes;

  private int sampleCount = 0;
  private final List<Float> fpsSamples      = new ArrayList<>();
  private final List<Float> cpuSamples      = new ArrayList<>();
  private final List<Float> javaMemSamples  = new ArrayList<>();
  private final List<Float> nativeMemSamples = new ArrayList<>();
  private final List<Float> rxKbpsSamples   = new ArrayList<>();
  private final List<Float> txKbpsSamples   = new ArrayList<>();

  private String cachedSecurityLevel = null; // null = not yet queried; "" = unavailable

  private final Player.Listener finalSummaryListener = new Player.Listener() {
    @Override
    public void onPlaybackStateChanged(int state) {
      if ((state == Player.STATE_ENDED || state == Player.STATE_IDLE) && sampleCount > 0) {
        logStats(/* isFinal= */ true);
        sampleCount = 0;
        fpsSamples.clear();
        cpuSamples.clear();
        javaMemSamples.clear();
        nativeMemSamples.clear();
        rxKbpsSamples.clear();
        txKbpsSamples.clear();
      }
    }
  };

  public DemoDebugTextViewHelper(ExoPlayer player, TextView textView) {
    super(player, textView);
    this.player = player;
    player.addListener(finalSummaryListener);
  }

  /** Logs final stats and removes the summary listener. Call before stop(). */
  /* package */ void flush() {
    if (sampleCount > 0) {
      logStats(/* isFinal= */ true);
      sampleCount = 0;
      fpsSamples.clear();
      cpuSamples.clear();
      javaMemSamples.clear();
      nativeMemSamples.clear();
      rxKbpsSamples.clear();
      txKbpsSamples.clear();
    }
    player.removeListener(finalSummaryListener);
  }

  @Override
  protected String getDebugString() {
    long now = android.os.SystemClock.elapsedRealtime();
    long elapsedMs = lastUpdateTimeMs == 0 ? 0 : now - lastUpdateTimeMs;

    // Snapshot all cumulative counters before computing strings
    DecoderCounters counters = player.getVideoDecoderCounters();
    long currentFrameCount = 0;
    if (counters != null) {
      counters.ensureUpdated();
      currentFrameCount = counters.renderedOutputBufferCount;
    }
    long cpuNow = Process.getElapsedCpuTime();
    int uid = Process.myUid();
    long rxNow = TrafficStats.getUidRxBytes(uid);
    long txNow = TrafficStats.getUidTxBytes(uid);

    // --- Compute raw float values ---
    float fps = (lastUpdateTimeMs == 0 || counters == null || elapsedMs <= 0)
        ? Float.NaN
        : (currentFrameCount - lastRenderedFrameCount) * 1000f / elapsedMs;
    float cpuPct = (lastUpdateTimeMs == 0 || elapsedMs <= 0)
        ? Float.NaN
        : (cpuNow - lastCpuTimeMs) * 100f / elapsedMs;
    Runtime rt = Runtime.getRuntime();
    float javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024);
    float javaTotalMb = rt.totalMemory() / (1024f * 1024);
    float nativeUsedMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024);
    boolean netSupported = rxNow != TrafficStats.UNSUPPORTED && txNow != TrafficStats.UNSUPPORTED;
    float rxKbps = (netSupported && lastUpdateTimeMs != 0 && elapsedMs > 0)
        ? (rxNow - lastRxBytes) * 1000f / elapsedMs / 1024f : Float.NaN;
    float txKbps = (netSupported && lastUpdateTimeMs != 0 && elapsedMs > 0)
        ? (txNow - lastTxBytes) * 1000f / elapsedMs / 1024f : Float.NaN;

    // --- Accumulate samples ---
    if (!Float.isNaN(fps))     fpsSamples.add(fps);
    if (!Float.isNaN(cpuPct))  cpuSamples.add(cpuPct);
    javaMemSamples.add(javaUsedMb);
    nativeMemSamples.add(nativeUsedMb);
    if (!Float.isNaN(rxKbps))  rxKbpsSamples.add(rxKbps);
    if (!Float.isNaN(txKbps))  txKbpsSamples.add(txKbps);

    sampleCount++;
    if (sampleCount >= LOG_INTERVAL_SAMPLES) {
      logStats(/* isFinal= */ false);
      sampleCount = 0;
      fpsSamples.clear();
      cpuSamples.clear();
      javaMemSamples.clear();
      nativeMemSamples.clear();
      rxKbpsSamples.clear();
      txKbpsSamples.clear();
    }

    // --- Build display strings ---
    String fpsStr   = buildFpsString(counters, fps);
    String cpuStr   = buildCpuString(cpuPct);
    String memStr   = buildMemString(javaUsedMb, javaTotalMb, nativeUsedMb);
    String netStr   = buildNetString(netSupported, rxKbps, txKbps);
    String mediaStr = buildMediaInfoString();
    String drmStr   = buildDrmInfoString();

    // Update state for next call
    lastRenderedFrameCount = currentFrameCount;
    lastCpuTimeMs = cpuNow;
    if (rxNow != TrafficStats.UNSUPPORTED) lastRxBytes = rxNow;
    if (txNow != TrafficStats.UNSUPPORTED) lastTxBytes = txNow;
    lastUpdateTimeMs = now;

    return fpsStr + cpuStr + memStr + netStr + mediaStr + drmStr + super.getDebugString();
  }

  private static String buildFpsString(DecoderCounters counters, float fps) {
    if (counters == null) {
      return "";
    }
    String text = Float.isNaN(fps) ? "FPS: --" : String.format(Locale.US, "FPS: %.1f", fps);
    return text + "\n";
  }

  private static String buildCpuString(float cpuPct) {
    String text = Float.isNaN(cpuPct) ? "CPU: --" : String.format(Locale.US, "CPU: %.1f%%", cpuPct);
    return text + "\n";
  }

  private static String buildMemString(float javaUsedMb, float javaTotalMb, float nativeUsedMb) {
    return String.format(
        Locale.US, "Mem: %.0f/%.0f MB java  %.0f MB native\n",
        javaUsedMb, javaTotalMb, nativeUsedMb);
  }

  private static String buildNetString(boolean netSupported, float rxKbps, float txKbps) {
    if (!netSupported) {
      return "";
    }
    String text = (Float.isNaN(rxKbps) || Float.isNaN(txKbps))
        ? "Net: --"
        : String.format(Locale.US, "Net: \u2193%.1f KB/s  \u2191%.1f KB/s", rxKbps, txKbps);
    return text + "\n";
  }

  private String buildMediaInfoString() {
    Format vf = player.getVideoFormat();
    Format af = player.getAudioFormat();
    String videoInfo;
    if (vf == null) {
      videoInfo = "no video";
    } else if (vf.frameRate != Format.NO_VALUE) {
      videoInfo = String.format(Locale.US, "%s %dx%d @%.2ffps",
          vf.sampleMimeType, vf.width, vf.height, vf.frameRate);
    } else {
      videoInfo = vf.sampleMimeType + " " + vf.width + "x" + vf.height;
    }
    String audioInfo = af != null
        ? af.sampleMimeType + " " + af.sampleRate + "Hz " + af.channelCount + "ch"
        : "no audio";
    String stateStr;
    switch (player.getPlaybackState()) {
      case Player.STATE_BUFFERING: stateStr = "buffering"; break;
      case Player.STATE_READY:     stateStr = "ready";     break;
      case Player.STATE_ENDED:     stateStr = "ended";     break;
      default:                     stateStr = "idle";      break;
    }
    long posSec = player.getContentPosition() / 1000;
    long bufSec = player.getContentBufferedPosition() / 1000;
    return String.format(Locale.US,
        "Media: %s | %s | %s | pos=%ds buf=%ds\n",
        videoInfo, audioInfo, stateStr, posSec, bufSec);
  }

  private String buildDrmInfoString() {
    MediaItem currentItem = player.getCurrentMediaItem();
    if (currentItem == null || currentItem.localConfiguration == null) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("URL: ").append(currentItem.localConfiguration.uri).append("\n");
    MediaItem.DrmConfiguration drmConfig = currentItem.localConfiguration.drmConfiguration;
    if (drmConfig != null) {
      String schemeName = getDrmSchemeName(drmConfig.scheme);
      String secLevel   = querySecurityLevel(drmConfig.scheme);
      sb.append("DRM: ").append(secLevel.isEmpty() ? schemeName : schemeName + " " + secLevel).append("\n");
      String keyUrl = drmConfig.licenseUri != null ? drmConfig.licenseUri.toString() : "none";
      sb.append("Key: ").append(keyUrl).append("\n");
    } else {
      sb.append("DRM: none\n");
    }
    return sb.toString();
  }

  private void logStats(boolean isFinal) {
    Format vf = player.getVideoFormat();
    Format af = player.getAudioFormat();
    String videoInfo;
    if (vf == null) {
      videoInfo = "no video";
    } else if (vf.frameRate != Format.NO_VALUE) {
      videoInfo = String.format(Locale.US, "%s %dx%d @%.2ffps",
          vf.sampleMimeType, vf.width, vf.height, vf.frameRate);
    } else {
      videoInfo = vf.sampleMimeType + " " + vf.width + "x" + vf.height;
    }
    String audioInfo = af != null
        ? af.sampleMimeType + " " + af.sampleRate + "Hz " + af.channelCount + "ch"
        : "no audio";
    String stateStr;
    switch (player.getPlaybackState()) {
      case Player.STATE_BUFFERING: stateStr = "buffering"; break;
      case Player.STATE_READY:     stateStr = "ready";     break;
      case Player.STATE_ENDED:     stateStr = "ended";     break;
      default:                     stateStr = "idle";      break;
    }
    long posSec = player.getContentPosition() / 1000;
    long bufSec = player.getContentBufferedPosition() / 1000;

    String header = isFinal ? "===== Final Playback Stats =====" : "===== ~10s Playback Stats =====";
    Log.d(LOG_TAG, header);
    Log.d(LOG_TAG, String.format(Locale.US,
        "Media: %s | %s | %s | pos=%ds buf=%ds", videoInfo, audioInfo, stateStr, posSec, bufSec));

    // --- DRM + URL info ---
    MediaItem currentItem = player.getCurrentMediaItem();
    if (currentItem != null && currentItem.localConfiguration != null) {
      Log.d(LOG_TAG, "URL:       " + currentItem.localConfiguration.uri);
      MediaItem.DrmConfiguration drmConfig = currentItem.localConfiguration.drmConfiguration;
      if (drmConfig != null) {
        String schemeName = getDrmSchemeName(drmConfig.scheme);
        String secLevel   = querySecurityLevel(drmConfig.scheme);
        String drmLine    = secLevel.isEmpty() ? schemeName : schemeName + " " + secLevel;
        Log.d(LOG_TAG, "DRM:       " + drmLine);
        String keyUrl = drmConfig.licenseUri != null ? drmConfig.licenseUri.toString() : "none";
        Log.d(LOG_TAG, "Key server:" + keyUrl);
      } else {
        Log.d(LOG_TAG, "DRM:       none (clear content)");
      }
    }

    if (!fpsSamples.isEmpty())
      Log.d(LOG_TAG, "FPS:        " + statsString(fpsSamples, "%.1f"));
    if (!cpuSamples.isEmpty())
      Log.d(LOG_TAG, "CPU:        " + statsString(cpuSamples, "%.1f") + " %");
    Log.d(LOG_TAG, "Mem java:   " + statsString(javaMemSamples, "%.0f") + " MB");
    Log.d(LOG_TAG, "Mem native: " + statsString(nativeMemSamples, "%.0f") + " MB");
    if (!rxKbpsSamples.isEmpty())
      Log.d(LOG_TAG, "Net \u2193:      " + statsString(rxKbpsSamples, "%.1f") + " KB/s");
    if (!txKbpsSamples.isEmpty())
      Log.d(LOG_TAG, "Net \u2191:      " + statsString(txKbpsSamples, "%.1f") + " KB/s");
    Log.d(LOG_TAG, "================================");
  }

  private static String getDrmSchemeName(UUID uuid) {
    if (C.WIDEVINE_UUID.equals(uuid))  return "Widevine";
    if (C.PLAYREADY_UUID.equals(uuid)) return "PlayReady";
    if (C.CLEARKEY_UUID.equals(uuid))  return "ClearKey";
    return "Unknown(" + uuid + ")";
  }

  /**
   * Returns security level string (e.g., "L1", "L3") for Widevine, empty string for other
   * schemes or on failure. Result is cached after the first successful query.
   */
  private String querySecurityLevel(UUID schemeUuid) {
    if (!C.WIDEVINE_UUID.equals(schemeUuid)) return "";
    if (cachedSecurityLevel != null) return cachedSecurityLevel;
    try {
      MediaDrm drm = new MediaDrm(schemeUuid);
      cachedSecurityLevel = drm.getPropertyString("securityLevel"); // "L1" or "L3"
      drm.release();
    } catch (Exception e) {
      cachedSecurityLevel = "";
    }
    return cachedSecurityLevel;
  }

  /** Returns "min=X  max=X  mean=X  median=X" for the given samples. */
  private static String statsString(List<Float> samples, String fmt) {
    if (samples.isEmpty()) return "--";
    List<Float> sorted = new ArrayList<>(samples);
    Collections.sort(sorted);
    float min = sorted.get(0);
    float max = sorted.get(sorted.size() - 1);
    float sum = 0;
    for (float v : samples) sum += v;
    float mean = sum / samples.size();
    int n = sorted.size();
    float median = (n % 2 == 1)
        ? sorted.get(n / 2)
        : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2f;
    return String.format(Locale.US,
        "min=" + fmt + "  max=" + fmt + "  mean=" + fmt + "  median=" + fmt,
        min, max, mean, median);
  }
}
