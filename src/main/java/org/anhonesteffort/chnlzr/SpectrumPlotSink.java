/*
 * Copyright (C) 2015 An Honest Effort LLC, coping.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.anhonesteffort.chnlzr;

import io.netty.channel.ChannelHandlerContext;
import org.anhonesteffort.dsp.ComplexNumber;
import org.anhonesteffort.dsp.dft.DftWidth;
import org.anhonesteffort.dsp.plot.SpectrumFrame;
import org.anhonesteffort.dsp.DynamicSink;
import org.anhonesteffort.dsp.sample.Samples;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;

public class SpectrumPlotSink implements DynamicSink<ByteBuffer> {

  private static final DftWidth DFT_WIDTH         = DftWidth.DFT_4096;
  private static final int      AVERAGING         = 20;
  private static final int      FRAME_RATE        = 25;
  private static final int      SAMPLE_QUEUE_SIZE = 15;

  private final SpectrumFrame spectrumFrame;

  public SpectrumPlotSink(ExecutorService executor, ChannelHandlerContext context) {
    spectrumFrame = new SpectrumFrame(executor, DFT_WIDTH, AVERAGING, FRAME_RATE, SAMPLE_QUEUE_SIZE);

    spectrumFrame.setSize(300, 300);
    spectrumFrame.setLocationRelativeTo(null);
    spectrumFrame.setVisible(true);
    spectrumFrame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        super.windowClosing(e);
        context.close();
      }
    });

    context.channel().closeFuture().addListener(close ->
        spectrumFrame.dispatchEvent(new WindowEvent(spectrumFrame, WindowEvent.WINDOW_CLOSING))
    );
  }

  @Override
  public void onSourceStateChange(Long sampleRate, Double frequency) {
    spectrumFrame.setTitle("channel: " + frequency + "Hz @ " + sampleRate + "sps");
    spectrumFrame.onSourceStateChange(sampleRate, frequency);
  }

  @Override
  public void consume(ByteBuffer bytes) {
    FloatBuffer     floats  = bytes.asFloatBuffer();
    ComplexNumber[] samples = new ComplexNumber[floats.limit() / 2];

    for (int i = 0; i < samples.length; i++) {
      samples[i] = new ComplexNumber(floats.get(i * 2), floats.get((i * 2) + 1));
    }

    spectrumFrame.consume(new Samples(samples));
  }

}
