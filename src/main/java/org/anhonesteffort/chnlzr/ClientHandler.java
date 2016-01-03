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
import org.capnproto.StructList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

import static org.anhonesteffort.chnlzr.Proto.BaseMessage;
import static org.anhonesteffort.chnlzr.Proto.ChannelRequest;
import static org.anhonesteffort.chnlzr.Proto.ChannelGrant;

public class ClientHandler extends ChannelHandlerAdapter {

  private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

  private final ExecutorService       executor;
  private final boolean               hostIsBrkr;
  private final ChannelRequest.Reader request;

  private Optional<SpectrumPlotSink> channelSink = Optional.empty();
  private boolean                    requestSent = false;

  public ClientHandler(ExecutorService executor, boolean hostIsBrkr, ChannelRequest.Reader request) {
    this.executor   = executor;
    this.hostIsBrkr = hostIsBrkr;
    this.request    = request;
  }

  @Override
  public void channelActive(ChannelHandlerContext context) {
    if (!hostIsBrkr)
      context.writeAndFlush(CapnpUtil.channelRequest(request));
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object response) {
    BaseMessage.Reader message = (BaseMessage.Reader) response;

    switch (message.getType()) {
      case ERROR:
        log.info("error code: " + message.getError().getCode());
        context.close();
        break;

      case BRKR_STATE:
        if (!hostIsBrkr || requestSent) {
          break;
        } else {
          requestSent = true;
        }

        StructList.Reader<ChannelGrant.Reader> grants      = message.getBrkrState().getGrants();
        Optional<ChannelGrant.Reader>          usableGrant =
            IntStream.range(0, grants.size())
                     .mapToObj(grants::get)
                     .filter(grant -> CapnpUtil.grantSatisfiesRequest(grant, request))
                     .findFirst();

        if (usableGrant.isPresent()) {
          log.info("existing channel grant satisfies request, multiplexing");
          context.writeAndFlush(CapnpUtil.multiplexRequest(usableGrant.get().getId()));
        } else {
          log.info("no existing channel grant satisfies request, requesting new grant");
          context.writeAndFlush(CapnpUtil.channelRequest(request));
        }
        break;

      case CHANNEL_STATE:
        if (!channelSink.isPresent())
          channelSink = Optional.of(new SpectrumPlotSink(executor, context));

        channelSink.get().onSourceStateChange(
            message.getChannelState().getSampleRate(),
            message.getChannelState().getCenterFrequency()
        );
        break;

      case SAMPLES:
        if (channelSink.isPresent())
          channelSink.get().consume(message.getSamples().getSamples());
        else {
          log.warn("received samples before channel state, closing");
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
