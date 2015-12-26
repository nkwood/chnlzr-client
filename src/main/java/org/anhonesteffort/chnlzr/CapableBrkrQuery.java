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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.anhonesteffort.chnlzr.pipeline.BaseMessageDecoder;
import org.anhonesteffort.chnlzr.pipeline.BaseMessageEncoder;
import org.capnproto.StructList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.anhonesteffort.chnlzr.Proto.BaseMessage;
import static org.anhonesteffort.chnlzr.Proto.HostId;
import static org.anhonesteffort.chnlzr.Proto.ChannelRequest;
import static org.anhonesteffort.chnlzr.Proto.Capabilities;

public class CapableBrkrQuery implements Callable<Boolean> {

  private static final Logger log = LoggerFactory.getLogger(CapableBrkrQuery.class);

  private final ChnlzrConfig          config;
  private final EventLoopGroup        workerGroup;
  private final HostId.Reader         brkrHost;
  private final ChannelRequest.Reader request;

  public CapableBrkrQuery(ChnlzrConfig          config,
                          EventLoopGroup        workerGroup,
                          HostId.Reader         brkrHost,
                          ChannelRequest.Reader request)
  {
    this.config      = config;
    this.workerGroup = workerGroup;
    this.brkrHost    = brkrHost;
    this.request     = request;
  }

  @Override
  public Boolean call() throws InterruptedException {
    Bootstrap        bootstrap        = new Bootstrap();
    BrkrStateHandler brkrStateHandler = new BrkrStateHandler();

    bootstrap.group(workerGroup)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.SO_KEEPALIVE, false)
             .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectionTimeoutMs())
             .handler(new ChannelInitializer<SocketChannel>() {
               @Override
               public void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("encoder", BaseMessageEncoder.INSTANCE);
                 ch.pipeline().addLast("decoder", new BaseMessageDecoder());
                 ch.pipeline().addLast("handler", brkrStateHandler);
               }
             });

    ChannelFuture connectFuture = bootstrap.connect(
        brkrHost.getHostname().toString(), brkrHost.getPort()
    );

    try {

      connectFuture.channel().closeFuture().await();
      return brkrStateHandler.isCapable();

    } finally {
      connectFuture.channel().close();
    }
  }

  private class BrkrStateHandler extends ChannelHandlerAdapter {

    private boolean isCapable = false;

    private boolean isLocal(Capabilities.Reader capabilities, ChannelRequest.Reader request) {
      return request.getMaxLocationDiff() <= 0d || Util.kmDistanceBetween(
          capabilities.getLatitude(), capabilities.getLongitude(),
          request.getLatitude(),      request.getLongitude()
      ) <= request.getMaxLocationDiff();
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object response) {
      BaseMessage.Reader message = (BaseMessage.Reader) response;

      switch (message.getType()) {
        case BRKR_STATE:
          StructList.Reader<Capabilities.Reader> capabilities = message.getBrkrState().getChnlzrs();

          isCapable =
              IntStream.range(0, capabilities.size())
                       .mapToObj(capabilities::get)
                       .filter(cap -> isLocal(cap, request))
                       .filter(cap -> request.getPolarization() == 0 ||
                                      cap.getPolarization()     == request.getPolarization())
                       .map(CapnpUtil::spec)
                       .anyMatch(cap -> cap.containsChannel(CapnpUtil.spec(request)));

          context.close();
          break;

        default:
          log.warn("received unexpected message type from channel broker: " + message.getType());
      }
    }

    public boolean isCapable() {
      return isCapable;
    }
  }
}
