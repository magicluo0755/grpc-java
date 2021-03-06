/*
 * Copyright 2018, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.services;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.grpc.ConnectivityState;
import io.grpc.Status;
import io.grpc.channelz.v1.Address;
import io.grpc.channelz.v1.Address.OtherAddress;
import io.grpc.channelz.v1.Address.TcpIpAddress;
import io.grpc.channelz.v1.Address.UdsAddress;
import io.grpc.channelz.v1.Channel;
import io.grpc.channelz.v1.ChannelData;
import io.grpc.channelz.v1.ChannelData.State;
import io.grpc.channelz.v1.ChannelRef;
import io.grpc.channelz.v1.GetServerSocketsResponse;
import io.grpc.channelz.v1.GetServersResponse;
import io.grpc.channelz.v1.GetTopChannelsResponse;
import io.grpc.channelz.v1.Server;
import io.grpc.channelz.v1.ServerData;
import io.grpc.channelz.v1.ServerRef;
import io.grpc.channelz.v1.Socket;
import io.grpc.channelz.v1.Socket.Builder;
import io.grpc.channelz.v1.SocketData;
import io.grpc.channelz.v1.SocketOption;
import io.grpc.channelz.v1.SocketOptionLinger;
import io.grpc.channelz.v1.SocketOptionTcpInfo;
import io.grpc.channelz.v1.SocketOptionTimeout;
import io.grpc.channelz.v1.SocketRef;
import io.grpc.channelz.v1.Subchannel;
import io.grpc.channelz.v1.SubchannelRef;
import io.grpc.internal.Channelz;
import io.grpc.internal.Channelz.ChannelStats;
import io.grpc.internal.Channelz.RootChannelList;
import io.grpc.internal.Channelz.ServerList;
import io.grpc.internal.Channelz.ServerSocketsList;
import io.grpc.internal.Channelz.ServerStats;
import io.grpc.internal.Channelz.SocketStats;
import io.grpc.internal.Channelz.TransportStats;
import io.grpc.internal.Instrumented;
import io.grpc.internal.WithLogId;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

/**
 * A static utility class for turning internal data structures into protos.
 */
final class ChannelzProtoUtil {
  private ChannelzProtoUtil() {
    // do not instantiate.
  }

  static ChannelRef toChannelRef(WithLogId obj) {
    return ChannelRef
        .newBuilder()
        .setChannelId(obj.getLogId().getId())
        .setName(obj.toString())
        .build();
  }

  static SubchannelRef toSubchannelRef(WithLogId obj) {
    return SubchannelRef
        .newBuilder()
        .setSubchannelId(obj.getLogId().getId())
        .setName(obj.toString())
        .build();
  }

  static ServerRef toServerRef(WithLogId obj) {
    return ServerRef
        .newBuilder()
        .setServerId(obj.getLogId().getId())
        .setName(obj.toString())
        .build();
  }

  static SocketRef toSocketRef(WithLogId obj) {
    return SocketRef
        .newBuilder()
        .setSocketId(obj.getLogId().getId())
        .setName(obj.toString())
        .build();
  }

  static Server toServer(Instrumented<ServerStats> obj) {
    ServerStats stats = getFuture(obj.getStats());
    Server.Builder builder = Server
        .newBuilder()
        .setRef(toServerRef(obj))
        .setData(toServerData(stats));
    for (Instrumented<SocketStats> listenSocket : stats.listenSockets) {
      builder.addListenSocket(toSocketRef(listenSocket));
    }
    return builder.build();
  }

  static ServerData toServerData(ServerStats stats) {
    return ServerData
        .newBuilder()
        .setCallsStarted(stats.callsStarted)
        .setCallsSucceeded(stats.callsSucceeded)
        .setCallsFailed(stats.callsFailed)
        .setLastCallStartedTimestamp(Timestamps.fromMillis(stats.lastCallStartedMillis))
        .build();
  }

  static Socket toSocket(Instrumented<SocketStats> obj) {
    SocketStats socketStats = getFuture(obj.getStats());
    Builder builder = Socket.newBuilder()
        .setRef(toSocketRef(obj))
        .setLocal(toAddress(socketStats.local));
    // listen sockets do not have remote nor data
    if (socketStats.remote != null) {
      builder.setRemote(toAddress(socketStats.remote));
    }
    builder.setData(extractSocketData(socketStats));
    return builder.build();
  }

  static Address toAddress(SocketAddress address) {
    Preconditions.checkNotNull(address);
    Address.Builder builder = Address.newBuilder();
    if (address instanceof InetSocketAddress) {
      InetSocketAddress inetAddress = (InetSocketAddress) address;
      builder.setTcpipAddress(
          TcpIpAddress
              .newBuilder()
              .setIpAddress(
                  ByteString.copyFrom(inetAddress.getAddress().getAddress()))
              .setPort(inetAddress.getPort())
              .build());
    } else if (address.getClass().getName().endsWith("io.netty.channel.unix.DomainSocketAddress")) {
      builder.setUdsAddress(
          UdsAddress
              .newBuilder()
              .setFilename(address.toString()) // DomainSocketAddress.toString returns filename
              .build());
    } else {
      builder.setOtherAddress(OtherAddress.newBuilder().setName(address.toString()).build());
    }
    return builder.build();
  }

  static SocketData extractSocketData(SocketStats socketStats) {
    SocketData.Builder builder = SocketData.newBuilder();
    if (socketStats.data != null) {
      TransportStats s = socketStats.data;
      builder
          .setStreamsStarted(s.streamsStarted)
          .setStreamsSucceeded(s.streamsSucceeded)
          .setStreamsFailed(s.streamsFailed)
          .setMessagesSent(s.messagesSent)
          .setMessagesReceived(s.messagesReceived)
          .setKeepAlivesSent(s.keepAlivesSent)
          .setLastLocalStreamCreatedTimestamp(
              Timestamps.fromNanos(s.lastLocalStreamCreatedTimeNanos))
          .setLastRemoteStreamCreatedTimestamp(
              Timestamps.fromNanos(s.lastRemoteStreamCreatedTimeNanos))
          .setLastMessageSentTimestamp(
              Timestamps.fromNanos(s.lastMessageSentTimeNanos))
          .setLastMessageReceivedTimestamp(
              Timestamps.fromNanos(s.lastMessageReceivedTimeNanos))
          .setLocalFlowControlWindow(
              Int64Value.of(s.localFlowControlWindow))
          .setRemoteFlowControlWindow(
              Int64Value.of(s.remoteFlowControlWindow));
    }
    builder.addAllOption(toSocketOptionsList(socketStats.socketOptions));
    return builder.build();
  }

  public static final String SO_LINGER = "SO_LINGER";
  public static final String SO_TIMEOUT = "SO_TIMEOUT";
  public static final String TCP_INFO = "TCP_INFO";

  static SocketOption toSocketOptionLinger(int lingerSeconds) {
    final SocketOptionLinger lingerOpt;
    if (lingerSeconds >= 0) {
      lingerOpt = SocketOptionLinger
          .newBuilder()
          .setActive(true)
          .setDuration(Durations.fromSeconds(lingerSeconds))
          .build();
    } else {
      lingerOpt = SocketOptionLinger.getDefaultInstance();
    }
    return SocketOption
        .newBuilder()
        .setName(SO_LINGER)
        .setAdditional(Any.pack(lingerOpt))
        .build();
  }

  static SocketOption toSocketOptionTimeout(String name, int timeoutMillis) {
    Preconditions.checkNotNull(name);
    return SocketOption
        .newBuilder()
        .setName(name)
        .setAdditional(
            Any.pack(
                SocketOptionTimeout
                    .newBuilder()
                    .setDuration(Durations.fromMillis(timeoutMillis))
                    .build()))
        .build();
  }

  static SocketOption toSocketOptionTcpInfo(Channelz.TcpInfo i) {
    SocketOptionTcpInfo tcpInfo = SocketOptionTcpInfo.newBuilder()
        .setTcpiState(i.state)
        .setTcpiCaState(i.caState)
        .setTcpiRetrans(i.retransmits)
        .setTcpiProbes(i.probes)
        .setTcpiBackoff(i.backoff)
        .setTcpiOptions(i.options)
        .setTcpiSndWscale(i.sndWscale)
        .setTcpiRcvWscale(i.rcvWscale)
        .setTcpiRto(i.rto)
        .setTcpiAto(i.ato)
        .setTcpiSndMss(i.sndMss)
        .setTcpiRcvMss(i.rcvMss)
        .setTcpiUnacked(i.unacked)
        .setTcpiSacked(i.sacked)
        .setTcpiLost(i.lost)
        .setTcpiRetrans(i.retrans)
        .setTcpiFackets(i.fackets)
        .setTcpiLastDataSent(i.lastDataSent)
        .setTcpiLastAckSent(i.lastAckSent)
        .setTcpiLastDataRecv(i.lastDataRecv)
        .setTcpiLastAckRecv(i.lastAckRecv)
        .setTcpiPmtu(i.pmtu)
        .setTcpiRcvSsthresh(i.rcvSsthresh)
        .setTcpiRtt(i.rtt)
        .setTcpiRttvar(i.rttvar)
        .setTcpiSndSsthresh(i.sndSsthresh)
        .setTcpiSndCwnd(i.sndCwnd)
        .setTcpiAdvmss(i.advmss)
        .setTcpiReordering(i.reordering)
        .build();
    return SocketOption
        .newBuilder()
        .setName(TCP_INFO)
        .setAdditional(Any.pack(tcpInfo))
        .build();
  }

  static SocketOption toSocketOptionAdditional(String name, String value) {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(value);
    return SocketOption.newBuilder().setName(name).setValue(value).build();
  }

  static List<SocketOption> toSocketOptionsList(Channelz.SocketOptions options) {
    Preconditions.checkNotNull(options);
    List<SocketOption> ret = new ArrayList<SocketOption>();
    if (options.lingerSeconds != null) {
      ret.add(toSocketOptionLinger(options.lingerSeconds));
    }
    if (options.soTimeoutMillis != null) {
      ret.add(toSocketOptionTimeout(SO_TIMEOUT, options.soTimeoutMillis));
    }
    if (options.tcpInfo != null) {
      ret.add(toSocketOptionTcpInfo(options.tcpInfo));
    }
    for (Entry<String, String> entry : options.others.entrySet()) {
      ret.add(toSocketOptionAdditional(entry.getKey(), entry.getValue()));
    }
    return ret;
  }

  static Channel toChannel(Instrumented<ChannelStats> channel) {
    ChannelStats stats = getFuture(channel.getStats());
    Channel.Builder channelBuilder = Channel
        .newBuilder()
        .setRef(toChannelRef(channel))
        .setData(extractChannelData(stats));
    for (WithLogId subchannel : stats.subchannels) {
      channelBuilder.addSubchannelRef(toSubchannelRef(subchannel));
    }

    return channelBuilder.build();
  }

  static ChannelData extractChannelData(Channelz.ChannelStats stats) {
    return ChannelData
        .newBuilder()
        .setTarget(stats.target)
        .setState(toState(stats.state))
        .setCallsStarted(stats.callsStarted)
        .setCallsSucceeded(stats.callsSucceeded)
        .setCallsFailed(stats.callsFailed)
        .setLastCallStartedTimestamp(Timestamps.fromMillis(stats.lastCallStartedMillis))
        .build();
  }

  static State toState(ConnectivityState state) {
    if (state == null) {
      return State.UNKNOWN;
    }
    try {
      return Enum.valueOf(State.class, state.name());
    } catch (IllegalArgumentException e) {
      return State.UNKNOWN;
    }
  }

  static Subchannel toSubchannel(Instrumented<ChannelStats> subchannel) {
    ChannelStats stats = getFuture(subchannel.getStats());
    Subchannel.Builder subchannelBuilder = Subchannel
        .newBuilder()
        .setRef(toSubchannelRef(subchannel))
        .setData(extractChannelData(stats));
    Preconditions.checkState(stats.sockets.isEmpty() || stats.subchannels.isEmpty());
    for (WithLogId childSocket : stats.sockets) {
      subchannelBuilder.addSocketRef(toSocketRef(childSocket));
    }
    for (WithLogId childSubchannel : stats.subchannels) {
      subchannelBuilder.addSubchannelRef(toSubchannelRef(childSubchannel));
    }
    return subchannelBuilder.build();
  }

  static GetTopChannelsResponse toGetTopChannelResponse(RootChannelList rootChannels) {
    GetTopChannelsResponse.Builder responseBuilder = GetTopChannelsResponse
        .newBuilder()
        .setEnd(rootChannels.end);
    for (Instrumented<ChannelStats> c : rootChannels.channels) {
      responseBuilder.addChannel(ChannelzProtoUtil.toChannel(c));
    }
    return responseBuilder.build();
  }

  static GetServersResponse toGetServersResponse(ServerList servers) {
    GetServersResponse.Builder responseBuilder = GetServersResponse
        .newBuilder()
        .setEnd(servers.end);
    for (Instrumented<ServerStats> s : servers.servers) {
      responseBuilder.addServer(ChannelzProtoUtil.toServer(s));
    }
    return responseBuilder.build();
  }

  static GetServerSocketsResponse toGetServerSocketsResponse(ServerSocketsList serverSockets) {
    GetServerSocketsResponse.Builder responseBuilder = GetServerSocketsResponse
        .newBuilder()
        .setEnd(serverSockets.end);
    for (WithLogId s : serverSockets.sockets) {
      responseBuilder.addSocketRef(ChannelzProtoUtil.toSocketRef(s));
    }
    return responseBuilder.build();
  }

  private static <T> T getFuture(ListenableFuture<T> future) {
    try {
      T ret = future.get();
      if (ret == null) {
        throw Status.UNIMPLEMENTED
            .withDescription("The entity's stats can not be retrieved. "
                + "If this is an InProcessTransport this is expected.")
            .asRuntimeException();
      }
      return ret;
    } catch (InterruptedException e) {
      throw Status.INTERNAL.withCause(e).asRuntimeException();
    } catch (ExecutionException e) {
      throw Status.INTERNAL.withCause(e).asRuntimeException();
    }
  }
}
