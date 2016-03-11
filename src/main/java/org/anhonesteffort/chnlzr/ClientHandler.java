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

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.capnproto.MessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.anhonesteffort.chnlzr.capnp.Proto.BaseMessage;

public class ClientHandler extends ChannelHandlerAdapter {

  private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

  private final ExecutorService executor;
  private final MessageBuilder  request;
  private final MulticastSource samples;

  private Optional<SpectrumPlotSink> channelSink = Optional.empty();

  public ClientHandler(ExecutorService executor, MessageBuilder request, MulticastSource samples) {
    this.executor = executor;
    this.request  = request;
    this.samples  = samples;
  }

  @Override
  public void channelActive(ChannelHandlerContext context) {
    context.writeAndFlush(request);
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object response) {
    BaseMessage.Reader message = (BaseMessage.Reader) response;

    switch (message.getType()) {
      case CHANNEL_RESPONSE:
        if (message.getChannelResponse().getError() != 0x00) {
          log.error("error code: " + message.getChannelResponse().getError());
          context.close();
        } else {
          channelSink = Optional.of(new SpectrumPlotSink(
              executor, context, message.getChannelResponse().getChannelId()
          ));
          samples.addSink(channelSink.get());
        }
        break;

      case CHANNEL_STATE:
        channelSink.get().onSourceStateChange(
            message.getChannelState().getSampleRate(),
            message.getChannelState().getCenterFrequency()
        );
        break;

      case CAPABILITIES:
        break;

      default:
        log.error("what is this: " + message.getType() + "?");
        context.close();
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext context) {
    if (channelSink.isPresent()) {
      samples.removeSink(channelSink.get());
    } else {
      channelSink = Optional.empty();
    }
  }

}
