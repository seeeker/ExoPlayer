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
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.util.DebugTextViewHelper;
import com.google.android.exoplayer2.util.MimeTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;

/** Extends DebugTextViewHelper to prepend live FPS, CPU, memory, and network metrics. */
/* package */ final class DemoDebugTextViewHelper extends DebugTextViewHelper
    implements AnalyticsListener {

  private static final String LOG_TAG = "ExoDemo";
  private static final String JSON_TAG = "ExoDemoJSON";
  private static final int LOG_INTERVAL_SAMPLES = 10;

  private final ExoPlayer player;
  private long lastRenderedFrameCount;
  private long lastUpdateTimeMs;
  private long lastCpuTimeMs;
  private long lastRxBytes;
  private long lastTxBytes;
  private long sessionStartRxBytes = -1; // -1 until first valid reading
  private long sessionStartTxBytes = -1;
  private long lastSessionRxBytes = 0;
  private long lastSessionTxBytes = 0;

  private int sampleCount = 0;
  private final List<Float> fpsSamples      = new ArrayList<>();
  private final List<Float> cpuSamples      = new ArrayList<>();
  private final List<Float> javaMemSamples  = new ArrayList<>();
  private final List<Float> nativeMemSamples = new ArrayList<>();
  private final List<Float> rxKbpsSamples   = new ArrayList<>();
  private final List<Float> txKbpsSamples   = new ArrayList<>();

  private String cachedSecurityLevel = null; // null = not yet queried; "" = unavailable

  @Nullable private Format lastSelectedVideoFormat = null;
  @Nullable private Format lastSelectedAudioFormat = null;

  @Nullable private String lastVideoSegmentUrl = null;
  @Nullable private String lastAudioSegmentUrl = null;
  @Nullable private String lastEncScheme = null;
  @Nullable private String note = null;

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
    player.addAnalyticsListener(this);
  }

  public void setNote(@Nullable String note) {
    this.note = note;
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
    player.removeAnalyticsListener(this);
  }

  @Override
  public void onLoadStarted(EventTime eventTime, LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData) {
    if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return;
    String url = loadEventInfo.uri.toString();
    if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
      lastVideoSegmentUrl = url;
    } else if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO) {
      lastAudioSegmentUrl = url;
    }
  }

  @Override
  public void onVideoInputFormatChanged(EventTime eventTime, Format format,
      @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    lastSelectedVideoFormat = format;
    if (format.drmInitData != null) {
      if (format.drmInitData.schemeType != null) {
        lastEncScheme = format.drmInitData.schemeType;
      } else {
        // WebM containers have no scheme_type field; MatroskaExtractor stamps SchemeData
        // with VIDEO_WEBM. WebM only supports AES-CTR (cenc-equivalent), so infer "cenc".
        for (int i = 0; i < format.drmInitData.schemeDataCount; i++) {
          if (MimeTypes.VIDEO_WEBM.equals(format.drmInitData.get(i).mimeType)) {
            lastEncScheme = "cenc";
            break;
          }
        }
      }
    }
    Log.d(LOG_TAG, "Selected video: " + buildRepresentationString(format)
        + (lastVideoSegmentUrl != null ? "  seg=" + lastVideoSegmentUrl : ""));
  }

  @Override
  public void onAudioInputFormatChanged(EventTime eventTime, Format format,
      @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    lastSelectedAudioFormat = format;
    Log.d(LOG_TAG, "Selected audio: " + buildRepresentationString(format)
        + (lastAudioSegmentUrl != null ? "  seg=" + lastAudioSegmentUrl : ""));
  }

  private static String buildRepresentationString(Format f) {
    StringBuilder sb = new StringBuilder();
    if (f.sampleMimeType != null) sb.append(f.sampleMimeType);
    if (f.width != Format.NO_VALUE)     sb.append("  ").append(f.width).append("x").append(f.height);
    if (f.frameRate != Format.NO_VALUE) sb.append(String.format(Locale.US, "@%.2ffps", f.frameRate));
    if (f.sampleRate != Format.NO_VALUE)
      sb.append("  ").append(f.sampleRate).append("Hz ").append(f.channelCount).append("ch");
    if (f.bitrate != Format.NO_VALUE)
      sb.append(String.format(Locale.US, "  bitrate=%d", f.bitrate));
    if (f.codecs != null)  sb.append("  codecs=").append(f.codecs);
    if (f.id != null)      sb.append("  id=").append(f.id);
    return sb.toString();
  }

  private static JSONObject buildRepresentationJson(Format f) throws Exception {
    JSONObject o = new JSONObject();
    if (f.sampleMimeType != null) o.put("mime", f.sampleMimeType);
    if (f.width != Format.NO_VALUE)     { o.put("width", f.width); o.put("height", f.height); }
    if (f.frameRate != Format.NO_VALUE) o.put("fps", round2(f.frameRate));
    if (f.sampleRate != Format.NO_VALUE){ o.put("sample_rate", f.sampleRate); o.put("channels", f.channelCount); }
    if (f.bitrate != Format.NO_VALUE)   o.put("bitrate", f.bitrate);
    if (f.codecs != null)  o.put("codecs", f.codecs);
    if (f.id != null)      o.put("id", f.id);
    return o;
  }

  @Override
  protected String getDebugString() {
    long now = android.os.SystemClock.elapsedRealtime();
    long elapsedMs = lastUpdateTimeMs == 0 ? 0 : now - lastUpdateTimeMs;

    // Snapshot all cumulative counters before computing strings
    DecoderCounters counters = player.getVideoDecoderCounters();
    long currentFrameCount = 0;
    long totalDropped = 0;
    if (counters != null) {
      counters.ensureUpdated();
      currentFrameCount = counters.renderedOutputBufferCount;
      totalDropped = counters.droppedBufferCount;
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
    if (netSupported && sessionStartRxBytes == -1) {
      sessionStartRxBytes = rxNow;
      sessionStartTxBytes = txNow;
    }
    if (netSupported) {
      lastSessionRxBytes = rxNow - sessionStartRxBytes;
      lastSessionTxBytes = txNow - sessionStartTxBytes;
    }
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
      int state = player.getPlaybackState();
      if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) {
        logStats(/* isFinal= */ false);
      }
      sampleCount = 0;
      fpsSamples.clear();
      cpuSamples.clear();
      javaMemSamples.clear();
      nativeMemSamples.clear();
      rxKbpsSamples.clear();
      txKbpsSamples.clear();
    }

    // --- Build display strings ---
    String fpsStr   = buildFpsString(counters, fps, currentFrameCount, totalDropped);
    String cpuStr   = buildCpuString(cpuPct);
    String memStr   = buildMemString(javaUsedMb, javaTotalMb, nativeUsedMb);
    String netStr   = buildNetString(netSupported, rxKbps, txKbps, lastSessionRxBytes, lastSessionTxBytes);
    String mediaStr = buildMediaInfoString();
    String drmStr   = buildDrmInfoString();

    // Update state for next call
    lastRenderedFrameCount = currentFrameCount;
    lastCpuTimeMs = cpuNow;
    if (rxNow != TrafficStats.UNSUPPORTED) lastRxBytes = rxNow;
    if (txNow != TrafficStats.UNSUPPORTED) lastTxBytes = txNow;
    lastUpdateTimeMs = now;

    String noteStr = (note != null) ? "Note: " + note + "\n" : "";
    return noteStr + fpsStr + cpuStr + memStr + netStr + mediaStr + drmStr + super.getDebugString();
  }

  private static String buildFpsString(
      DecoderCounters counters, float fps, long played, long totalDropped) {
    if (counters == null) {
      return "";
    }
    String text = Float.isNaN(fps) ? "FPS: --" : String.format(Locale.US, "FPS: %.1f", fps);
    text += String.format(Locale.US, "  played: %d", played);
    if (totalDropped > 0) {
      text += String.format(Locale.US, "  drop: %d", totalDropped);
    }
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

  private static String buildNetString(
      boolean netSupported, float rxKbps, float txKbps,
      long sessionRxBytes, long sessionTxBytes) {
    if (!netSupported) {
      return "";
    }
    String rateStr = (Float.isNaN(rxKbps) || Float.isNaN(txKbps))
        ? "Net: --"
        : String.format(Locale.US, "Net: \u2193%.1f KB/s  \u2191%.1f KB/s", rxKbps, txKbps);
    String totalStr = String.format(Locale.US, "  (\u2193%s  \u2191%s)",
        formatBytes(sessionRxBytes), formatBytes(sessionTxBytes));
    return rateStr + totalStr + "\n";
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
      String drmLabel = secLevel.isEmpty() ? schemeName : schemeName + " " + secLevel;
      if (lastEncScheme != null) drmLabel += " (" + lastEncScheme + ")";
      sb.append("DRM: ").append(drmLabel).append("\n");
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
    if (note != null) {
      Log.d(LOG_TAG, "Note:       " + note);
    }
    Log.d(LOG_TAG, String.format(Locale.US,
        "Media: %s | %s | %s | pos=%ds buf=%ds", videoInfo, audioInfo, stateStr, posSec, bufSec));
    if (lastSelectedVideoFormat != null)
      Log.d(LOG_TAG, "Video repr: " + buildRepresentationString(lastSelectedVideoFormat));
    if (lastSelectedAudioFormat != null)
      Log.d(LOG_TAG, "Audio repr: " + buildRepresentationString(lastSelectedAudioFormat));
    if (lastVideoSegmentUrl != null)
      Log.d(LOG_TAG, "Video seg:  " + lastVideoSegmentUrl);
    if (lastAudioSegmentUrl != null)
      Log.d(LOG_TAG, "Audio seg:  " + lastAudioSegmentUrl);

    // --- DRM + URL info ---
    MediaItem currentItem = player.getCurrentMediaItem();
    if (currentItem != null && currentItem.localConfiguration != null) {
      Log.d(LOG_TAG, "URL:       " + currentItem.localConfiguration.uri);
      MediaItem.DrmConfiguration drmConfig = currentItem.localConfiguration.drmConfiguration;
      if (drmConfig != null) {
        String schemeName = getDrmSchemeName(drmConfig.scheme);
        String secLevel   = querySecurityLevel(drmConfig.scheme);
        String drmLine = secLevel.isEmpty() ? schemeName : schemeName + " " + secLevel;
        if (lastEncScheme != null) drmLine += " (" + lastEncScheme + ")";
        Log.d(LOG_TAG, "DRM:       " + drmLine);
        String keyUrl = drmConfig.licenseUri != null ? drmConfig.licenseUri.toString() : "none";
        Log.d(LOG_TAG, "Key server:" + keyUrl);
      } else {
        Log.d(LOG_TAG, "DRM:       none (clear content)");
      }
    }

    if (!fpsSamples.isEmpty())
      Log.d(LOG_TAG, "FPS:        " + statsString(fpsSamples, "%.1f"));
    DecoderCounters vc = player.getVideoDecoderCounters();
    if (vc != null) {
      vc.ensureUpdated();
      Log.d(LOG_TAG, "Played:     " + vc.renderedOutputBufferCount + " frames (total)");
      if (vc.droppedBufferCount > 0)
        Log.d(LOG_TAG, "Dropped:    " + vc.droppedBufferCount + " frames (total)");
    }
    if (!cpuSamples.isEmpty())
      Log.d(LOG_TAG, "CPU:        " + statsString(cpuSamples, "%.1f") + " %");
    Log.d(LOG_TAG, "Mem java:   " + statsString(javaMemSamples, "%.0f") + " MB");
    Log.d(LOG_TAG, "Mem native: " + statsString(nativeMemSamples, "%.0f") + " MB");
    if (!rxKbpsSamples.isEmpty())
      Log.d(LOG_TAG, "Net \u2193:      " + statsString(rxKbpsSamples, "%.1f") + " KB/s");
    if (!txKbpsSamples.isEmpty())
      Log.d(LOG_TAG, "Net \u2191:      " + statsString(txKbpsSamples, "%.1f") + " KB/s");
    if (sessionStartRxBytes != -1) {
      Log.d(LOG_TAG, "Net \u2193 total: " + formatBytes(lastSessionRxBytes));
      Log.d(LOG_TAG, "Net \u2191 total: " + formatBytes(lastSessionTxBytes));
    }
    Log.d(LOG_TAG, "================================");
    logStatsJson(isFinal);
  }

  private void logStatsJson(boolean isFinal) {
    try {
      JSONObject json = new JSONObject();
      json.put("type", isFinal ? "final" : "interval");
      if (note != null) {
        json.put("note", note);
      }

      // media
      JSONObject media = new JSONObject();
      Format vf = player.getVideoFormat();
      Format af = player.getAudioFormat();
      if (vf == null) {
        media.put("video", JSONObject.NULL);
      } else if (vf.frameRate != Format.NO_VALUE) {
        media.put("video", String.format(Locale.US, "%s %dx%d @%.2ffps",
            vf.sampleMimeType, vf.width, vf.height, vf.frameRate));
      } else {
        media.put("video", vf.sampleMimeType + " " + vf.width + "x" + vf.height);
      }
      media.put("audio", af != null
          ? af.sampleMimeType + " " + af.sampleRate + "Hz " + af.channelCount + "ch"
          : JSONObject.NULL);
      String stateStr;
      switch (player.getPlaybackState()) {
        case Player.STATE_BUFFERING: stateStr = "buffering"; break;
        case Player.STATE_READY:     stateStr = "ready";     break;
        case Player.STATE_ENDED:     stateStr = "ended";     break;
        default:                     stateStr = "idle";      break;
      }
      media.put("state", stateStr);
      media.put("pos_s", player.getContentPosition() / 1000);
      media.put("buf_s", player.getContentBufferedPosition() / 1000);
      json.put("media", media);
      if (lastSelectedVideoFormat != null)
        json.put("selected_video", buildRepresentationJson(lastSelectedVideoFormat));
      if (lastSelectedAudioFormat != null)
        json.put("selected_audio", buildRepresentationJson(lastSelectedAudioFormat));
      if (lastVideoSegmentUrl != null) json.put("video_seg_url", lastVideoSegmentUrl);
      if (lastAudioSegmentUrl != null) json.put("audio_seg_url", lastAudioSegmentUrl);

      // URL + DRM
      MediaItem currentItem = player.getCurrentMediaItem();
      if (currentItem != null && currentItem.localConfiguration != null) {
        json.put("url", currentItem.localConfiguration.uri.toString());
        MediaItem.DrmConfiguration drmConfig = currentItem.localConfiguration.drmConfiguration;
        if (drmConfig != null) {
          String schemeName = getDrmSchemeName(drmConfig.scheme);
          String secLevel   = querySecurityLevel(drmConfig.scheme);
          json.put("drm", secLevel.isEmpty() ? schemeName : schemeName + " " + secLevel);
          if (lastEncScheme != null) json.put("enc_scheme", lastEncScheme);
          json.put("key_server", drmConfig.licenseUri != null
              ? drmConfig.licenseUri.toString() : JSONObject.NULL);
        } else {
          json.put("drm", "none");
        }
      }

      // per-metric stats (key omitted when no samples)
      json.put("fps",           statsJson(fpsSamples));
      DecoderCounters vc2 = player.getVideoDecoderCounters();
      if (vc2 != null) {
        vc2.ensureUpdated();
        json.put("played_frames_total", vc2.renderedOutputBufferCount);
        json.put("dropped_frames_total", vc2.droppedBufferCount);
      }
      json.put("cpu_pct",       statsJson(cpuSamples));
      json.put("mem_java_mb",   statsJson(javaMemSamples));
      json.put("mem_native_mb", statsJson(nativeMemSamples));
      json.put("net_rx_kbps",   statsJson(rxKbpsSamples));
      json.put("net_tx_kbps",   statsJson(txKbpsSamples));
      if (sessionStartRxBytes != -1) {
        json.put("net_rx_total_bytes", lastSessionRxBytes);
        json.put("net_tx_total_bytes", lastSessionTxBytes);
      }

      Log.d(JSON_TAG, json.toString());
    } catch (Exception e) {
      // ignore — JSON logging is best-effort
    }
  }

  private static JSONObject statsJson(List<Float> samples) {
    if (samples.isEmpty()) return null;
    try {
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
      JSONObject obj = new JSONObject();
      obj.put("min",    round2(min));
      obj.put("max",    round2(max));
      obj.put("mean",   round2(mean));
      obj.put("median", round2(median));
      return obj;
    } catch (Exception e) {
      return null;
    }
  }

  private static double round2(float v) {
    return Math.round(v * 100.0) / 100.0;
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

  private static String formatBytes(long bytes) {
    if (bytes < 0) return "--";
    if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024));
    if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
    return bytes + " B";
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
