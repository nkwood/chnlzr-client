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
import org.anhonesteffort.chnlzr.pipeline.BaseMessageDecoder;
import org.anhonesteffort.chnlzr.pipeline.BaseMessageEncoder;
import org.anhonesteffort.chnlzr.pipeline.IdleStateHeartbeatWriter;
import pl.edu.icm.jlargearrays.ConcurrencyUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.anhonesteffort.chnlzr.Proto.ChannelRequest;
import static org.anhonesteffort.chnlzr.Proto.HostId;

public class ChnlzrClient {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private final ChnlzrConfig          config;
  private final boolean               hostIsBrkr;
  private final String                hostname;
  private final int                   hostPort;
  private final ChannelRequest.Reader channel;

  public ChnlzrClient(ChnlzrConfig          config,
                      boolean               hostIsBrkr,
                      String                hostname,
                      int                   hostPort,
                      ChannelRequest.Reader channel)
  {
    this.config     = config;
    this.hostIsBrkr = hostIsBrkr;
    this.hostname   = hostname;
    this.hostPort   = hostPort;
    this.channel    = channel;
  }

  public void run() throws InterruptedException, TimeoutException, ExecutionException {
    EventLoopGroup          workerGroup   = new NioEventLoopGroup();
    Bootstrap               bootstrap     = new Bootstrap();
    Optional<HostId.Reader> capableBrkr   = Optional.empty();
    Optional<ChannelFuture> channelFuture = Optional.empty();

    try {

      if (hostIsBrkr) {
        Future<List<HostId.Reader>> brkrHosts = executor.submit(
            new BrkrHostsLookup(config, workerGroup, hostname, hostPort)
        );

        for (HostId.Reader brkrHost : brkrHosts.get(10000, TimeUnit.MILLISECONDS)) {
          Future<Boolean> isBrkrCapable = executor.submit(
              new CapableBrkrQuery(config, workerGroup, brkrHost, channel)
          );

          if (isBrkrCapable.get(10000, TimeUnit.MILLISECONDS)) {
            capableBrkr = Optional.of(brkrHost);
            break;
          }
        }
      }

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
                   ch.pipeline().addLast("handler",    new ClientHandler(executor, hostIsBrkr, channel));
                 }
               });

      if (hostIsBrkr && !capableBrkr.isPresent()) {
        System.out.println("no available channel brokers are capable of serving request");
      } else if (hostIsBrkr) {
        channelFuture = Optional.of(bootstrap.connect(
            capableBrkr.get().getHostname().toString(), capableBrkr.get().getPort()
        ).sync());
      } else {
        channelFuture = Optional.of(bootstrap.connect(hostname, hostPort).sync());
      }

      if (channelFuture.isPresent())
        channelFuture.get().channel().closeFuture().sync();

    } finally {
      executor.shutdownNow();
      workerGroup.shutdownGracefully();
      ConcurrencyUtils.shutdownThreadPoolAndAwaitTermination();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.out.println("java -jar idk.jar <host uri> <frequency> <bandwidth> <sample rate>");
      System.out.println("host uri = chnlzr://localhost:8080 | brkr://localhost:9090");
      System.exit(1);
    }

    boolean hostIsBrkr = args[0].startsWith("brkr");
    String  hostname   = args[0].split("://")[1].split(":")[0];
    int     port       = Integer.parseInt(args[0].split("://")[1].split(":")[1]);

    new ChnlzrClient(
        new ChnlzrConfig(),
        hostIsBrkr, hostname, port,
        CapnpUtil.channelRequest(
            0d, 0d, 0d, 0,
            Double.parseDouble(args[1]),
            Double.parseDouble(args[2]),
            Long.parseLong(args[3]),
            150l
        )
    ).run();
  }

}