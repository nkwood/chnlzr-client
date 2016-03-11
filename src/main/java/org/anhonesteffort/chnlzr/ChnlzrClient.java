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
import org.anhonesteffort.chnlzr.capnp.CapnpUtil;
import org.anhonesteffort.chnlzr.capnp.BaseMessageDecoder;
import org.anhonesteffort.chnlzr.capnp.BaseMessageEncoder;
import org.anhonesteffort.chnlzr.netty.IdleStateHeartbeatWriter;
import org.capnproto.MessageBuilder;
import pl.edu.icm.jlargearrays.ConcurrencyUtils;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ChnlzrClient {

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  private final ChnlzrConfig   config;
  private final String         tcpHostname;
  private final int            tcpHostPort;
  private final String         multicastAddress;
  private final int            multicastPort;
  private final MessageBuilder request;

  public ChnlzrClient(ChnlzrConfig   config,
                      String         tcpHostname,
                      int            tcpHostPort,
                      String         multicastAddress,
                      int            multicastPort,
                      MessageBuilder request)
  {
    this.config           = config;
    this.tcpHostname      = tcpHostname;
    this.tcpHostPort      = tcpHostPort;
    this.multicastAddress = multicastAddress;
    this.multicastPort    = multicastPort;
    this.request          = request;
  }

  public void run() throws IOException, ExecutionException, InterruptedException {
    final MulticastSource samplesIn     = new MulticastSource(multicastAddress, multicastPort, config.samplesPerMessage());
    final Future          samplesFuture = executor.submit(samplesIn);

    final EventLoopGroup workerGroup = new NioEventLoopGroup();
    final Bootstrap      bootstrap   = new Bootstrap();

    try {

      bootstrap.group(workerGroup)
               .channel(NioSocketChannel.class)
               .option(ChannelOption.SO_KEEPALIVE, true)
               .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectionTimeoutMs())
               .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                   ch.pipeline().addLast("idle state", new IdleStateHandler(0, 0, config.idleStateThresholdMs(), TimeUnit.MILLISECONDS));
                   ch.pipeline().addLast("heartbeat",  IdleStateHeartbeatWriter.INSTANCE);
                   ch.pipeline().addLast("encoder",    BaseMessageEncoder.INSTANCE);
                   ch.pipeline().addLast("decoder",    new BaseMessageDecoder());
                   ch.pipeline().addLast("handler",    new ClientHandler(executor, request, samplesIn));
                 }
               });

      ChannelFuture channelFuture = bootstrap.connect(tcpHostname, tcpHostPort).sync();
      channelFuture.channel().closeFuture().sync();

    } finally {
      samplesIn.close();
      samplesFuture.cancel(true);
      executor.shutdownNow();
      workerGroup.shutdownGracefully();
      ConcurrencyUtils.shutdownThreadPoolAndAwaitTermination();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      System.out.println("$ ./run-client.sh <tcp uri> <udp uri> <frequency> <bandwidth> <sample rate>");
      System.out.println("tcp uri = localhost:7070");
      System.out.println("udp uri = 239.255.20.20:6644");
      System.exit(1);
    }

    String tcpHost    = args[0].split(":")[0];
    int    tcpPort    = Integer.parseInt(args[0].split(":")[1]);
    String udpAddress = args[1].split(":")[0];
    int    udpPort    = Integer.parseInt(args[1].split(":")[1]);

    new ChnlzrClient(
        new ChnlzrConfig(),
        tcpHost, tcpPort, udpAddress, udpPort,
        CapnpUtil.request(
            0,
            Double.parseDouble(args[2]),
            Double.parseDouble(args[3]),
            Long.parseLong(args[4]),
            150l
        )
    ).run();
  }

}