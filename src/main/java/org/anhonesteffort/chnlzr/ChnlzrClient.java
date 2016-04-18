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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.anhonesteffort.chnlzr.capnp.BaseMessageDecoder;
import org.anhonesteffort.chnlzr.capnp.BaseMessageEncoder;
import org.anhonesteffort.chnlzr.capnp.ProtoFactory;
import org.anhonesteffort.chnlzr.netty.IdleStateHeartbeatWriter;
import pl.edu.icm.jlargearrays.ConcurrencyUtils;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.anhonesteffort.chnlzr.capnp.Proto.ChannelRequest;

public class ChnlzrClient {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private final ChnlzrConfig          config;
  private final String                hostname;
  private final int                   hostPort;
  private final ChannelRequest.Reader request;

  public ChnlzrClient(ChnlzrConfig          config,
                      String                hostname,
                      int                   hostPort,
                      ChannelRequest.Reader request)
  {
    this.config   = config;
    this.hostname = hostname;
    this.hostPort = hostPort;
    this.request  = request;
  }

  public void run() throws InterruptedException, TimeoutException, ExecutionException {
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    Bootstrap      bootstrap   = new Bootstrap();

    try {

      bootstrap.group(workerGroup)
               .channel(NioSocketChannel.class)
               .option(ChannelOption.SO_KEEPALIVE, true)
               .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectionTimeoutMs())
               .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, config.bufferHighWaterMark())
               .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, config.bufferLowWaterMark())
               .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                   ch.pipeline().addLast("idle state", new IdleStateHandler(0, 0, config.idleStateThresholdMs(), TimeUnit.MILLISECONDS));
                   ch.pipeline().addLast("heartbeat",  IdleStateHeartbeatWriter.INSTANCE);
                   ch.pipeline().addLast("encoder",    BaseMessageEncoder.INSTANCE);
                   ch.pipeline().addLast("decoder",    new BaseMessageDecoder());
                   ch.pipeline().addLast("handler",    new ClientHandler(executor, request));
                 }
               });

      ChannelFuture channelFuture = bootstrap.connect(hostname, hostPort).sync();
      channelFuture.channel().closeFuture().sync();

    } finally {
      executor.shutdownNow();
      workerGroup.shutdownGracefully();
      ConcurrencyUtils.shutdownThreadPoolAndAwaitTermination();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.out.println("$ ./run-client.sh <host uri> <frequency> <bandwidth> <sample rate>");
      System.out.println("host uri = chnlzr://localhost:7070");
      System.exit(1);
    }

    String hostname = args[0].split("://")[1].split(":")[0];
    int    port     = Integer.parseInt(args[0].split("://")[1].split(":")[1]);

    new ChnlzrClient(
        new ChnlzrConfig(),
        hostname, port,
        new ProtoFactory().channelRequest(
            Double.parseDouble(args[1]),
            Double.parseDouble(args[2]),
            Long.parseLong(args[3]),
            150l
        )
    ).run();
  }

}