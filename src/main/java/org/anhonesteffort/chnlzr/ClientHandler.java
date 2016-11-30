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

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.anhonesteffort.chnlzr.capnp.ProtoFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.anhonesteffort.chnlzr.capnp.Proto.BaseMessage;
import static org.anhonesteffort.chnlzr.capnp.Proto.ChannelRequest;

public class ClientHandler extends ChannelHandlerAdapter {

  private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
  private final ProtoFactory proto = new ProtoFactory();

  private final ExecutorService       executor;
  private final ChannelRequest.Reader request;
  private Optional<SpectrumPlotSink>  channelSink = Optional.empty();

  public ClientHandler(ExecutorService executor, ChannelRequest.Reader request) {
    this.executor = executor;
    this.request  = request;
  }

  @Override
  public void channelActive(ChannelHandlerContext context) {
    context.writeAndFlush(proto.channelRequest(request));
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object response) {
    BaseMessage.Reader message = (BaseMessage.Reader) response;

    switch (message.getType()) {
      case ERROR:
        log.error("error code: " + message.getError().getCode());
        context.close();
        break;

      case CHANNEL_STATE:
        if (!channelSink.isPresent()) {
          channelSink = Optional.of(new SpectrumPlotSink(executor, context));
        }

        channelSink.get().onSourceStateChange(
            message.getChannelState().getSampleRate(),
            message.getChannelState().getCenterFrequency()
        );
        break;

      case SAMPLES:
        if (channelSink.isPresent()) {
          channelSink.get().consume(message.getSamples().getSamples().asByteBuffer());
        } else {
          log.error("received samples before channel state, closing");
          context.close();
        }
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
    channelSink = Optional.empty();
  }

}
