/*
 * Copyright (C) 2016 An Honest Effort LLC.
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

import org.anhonesteffort.chnlzr.udp.ChannelSamples;
import org.anhonesteffort.chnlzr.udp.SamplesDatagramDecoder;
import org.anhonesteffort.dsp.ConcurrentSource;
import org.anhonesteffort.dsp.Sink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MulticastSource extends ConcurrentSource<ChannelSamples, Sink<ChannelSamples>> {

  private static final Logger log = LoggerFactory.getLogger(MulticastSource.class);

  private final SamplesDatagramDecoder decoder = new SamplesDatagramDecoder();
  private final MulticastSocket socket;
  private final InetAddress address;
  private final int buffSize;

  public MulticastSource(String address, int port, int samplesPerMessage) throws IOException {
    buffSize     = (samplesPerMessage * 2 * 4) + 8;
    this.address = InetAddress.getByName(address);
    socket       = new MulticastSocket(port);

    socket.joinGroup(this.address);
  }

  @Override
  public Void call() throws IOException {
    try {

      while (true) {
        DatagramPacket packet = new DatagramPacket(new byte[buffSize], buffSize);
        socket.receive(packet);

        ChannelSamples samples = decoder.decode(packet);
        sinks.forEach(sink -> sink.consume(samples));
      }

    } catch (IOException e) {
      log.error("multicast socket error", e);
    } finally {
      socket.leaveGroup(address);
      socket.close();
    }

    return null;
  }

}
