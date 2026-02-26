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

import android.widget.TextView;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.util.DebugTextViewHelper;
import java.util.Locale;

/** Extends DebugTextViewHelper to prepend a live rendered-FPS counter. */
/* package */ final class DemoDebugTextViewHelper extends DebugTextViewHelper {

  private final ExoPlayer player;
  private long lastRenderedFrameCount;
  private long lastUpdateTimeMs;

  public DemoDebugTextViewHelper(ExoPlayer player, TextView textView) {
    super(player, textView);
    this.player = player;
  }

  @Override
  protected String getDebugString() {
    return getFpsString() + super.getDebugString();
  }

  private String getFpsString() {
    DecoderCounters counters = player.getVideoDecoderCounters();
    if (counters == null) {
      return "";
    }
    counters.ensureUpdated();
    long now = android.os.SystemClock.elapsedRealtime();
    long currentCount = counters.renderedOutputBufferCount;
    String fpsText;
    if (lastUpdateTimeMs == 0) {
      fpsText = "FPS: --";
    } else {
      long elapsedMs = now - lastUpdateTimeMs;
      float fps = elapsedMs > 0 ? (currentCount - lastRenderedFrameCount) * 1000f / elapsedMs : 0f;
      fpsText = String.format(Locale.US, "FPS: %.1f", fps);
    }
    lastRenderedFrameCount = currentCount;
    lastUpdateTimeMs = now;
    return fpsText + "\n";
  }
}
