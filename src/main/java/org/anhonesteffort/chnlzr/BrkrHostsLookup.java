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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.anhonesteffort.chnlzr.Proto.BaseMessage;
import static org.anhonesteffort.chnlzr.Proto.HostId;

public class BrkrHostsLookup implements Callable<List<HostId.Reader>> {

  private static final Logger log = LoggerFactory.getLogger(BrkrHostsLookup.class);

  private final ChnlzrConfig   config;
  private final EventLoopGroup workerGroup;
  private final String         brokerHostname;
  private final int            brokerPort;

  public BrkrHostsLookup(ChnlzrConfig   config,
                         EventLoopGroup workerGroup,
                         String         brokerHostname,
                         int            brokerPort)
  {
    this.config         = config;
    this.workerGroup    = workerGroup;
    this.brokerHostname = brokerHostname;
    this.brokerPort     = brokerPort;
  }

  @Override
  public List<HostId.Reader> call() throws InterruptedException {
    Bootstrap          bootstrap       = new Bootstrap();
    GetBrkrListHandler brkrListHandler = new GetBrkrListHandler();

    bootstrap.group(workerGroup)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.SO_KEEPALIVE, false)
             .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectionTimeoutMs())
             .handler(new ChannelInitializer<SocketChannel>() {
               @Override
               public void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("encoder", BaseMessageEncoder.INSTANCE);
                 ch.pipeline().addLast("decoder", new BaseMessageDecoder());
                 ch.pipeline().addLast("handler", brkrListHandler);
               }
             });

    ChannelFuture connectFuture = bootstrap.connect(brokerHostname, brokerPort);

    try {

      connectFuture.channel().closeFuture().await();
      return brkrListHandler.hosts();

    } finally {
      connectFuture.channel().close();
    }
  }

  private static class GetBrkrListHandler extends ChannelHandlerAdapter {

    private final List<HostId.Reader> hosts = new LinkedList<>();

    @Override
    public void channelActive(ChannelHandlerContext context) {
      context.writeAndFlush(CapnpUtil.getBrkrList())
             .addListener(new CloseOnFutureErrorListener(log, "write of GET_BRKR_LIST message failed"));
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object response) {
      BaseMessage.Reader message = (BaseMessage.Reader) response;

      switch (message.getType()) {
        case BRKR_LIST:
          StructList.Reader<HostId.Reader> brkrs = message.getBrkrList().getChnlbrkrs();
          IntStream.range(0, brkrs.size()).forEach(i -> hosts.add(brkrs.get(i)));
          context.close();
          break;

        case BRKR_STATE:
          break;

        default:
          log.warn("received unexpected message type from channel broker: " + message.getType());
      }
    }

    public List<HostId.Reader> hosts() {
      return hosts;
    }
  }
}
