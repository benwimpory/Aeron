/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver.media;

import org.agrona.BufferUtil;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.errors.DistinctErrorLog;
import io.aeron.driver.Configuration;
import io.aeron.protocol.HeaderFlyweight;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

import static io.aeron.driver.Configuration.RECEIVE_BYTE_BUFFER_LENGTH;
import static java.net.StandardSocketOptions.IP_MULTICAST_TTL;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_SNDBUF;
import static io.aeron.logbuffer.FrameDescriptor.frameVersion;
import static org.agrona.BitUtil.CACHE_LINE_LENGTH;

public abstract class UdpChannelTransport implements AutoCloseable
{
    protected InetSocketAddress bindAddress;
    protected InetSocketAddress endPointAddress;
    protected InetSocketAddress connectAddress;
    protected SelectionKey selectionKey;
    protected UdpTransportPoller transportPoller;
    protected final UdpChannel udpChannel;
    protected final DistinctErrorLog errorLog;
    protected final ByteBuffer receiveByteBuffer =
        BufferUtil.allocateDirectAligned(RECEIVE_BYTE_BUFFER_LENGTH, CACHE_LINE_LENGTH * 2);
    protected final UnsafeBuffer receiveBuffer = new UnsafeBuffer(receiveByteBuffer);
    protected DatagramChannel sendDatagramChannel;
    protected DatagramChannel receiveDatagramChannel;

    public UdpChannelTransport(
        final UdpChannel udpChannel,
        final InetSocketAddress endPointAddress,
        final InetSocketAddress bindAddress,
        final InetSocketAddress connectAddress,
        final DistinctErrorLog errorLog)
    {
        this.udpChannel = udpChannel;
        this.errorLog = errorLog;
        this.endPointAddress = endPointAddress;
        this.bindAddress = bindAddress;
        this.connectAddress = connectAddress;
    }

    /**
     * Create the underlying channel for reading and writing.
     */
    public void openDatagramChannel()
    {
        try
        {
            sendDatagramChannel = DatagramChannel.open(udpChannel.protocolFamily());
            receiveDatagramChannel = sendDatagramChannel;
            if (udpChannel.isMulticast())
            {
                final NetworkInterface localInterface = udpChannel.localInterface();

                if (null != connectAddress)
                {
                    receiveDatagramChannel = DatagramChannel.open(udpChannel.protocolFamily());
                }

                receiveDatagramChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                receiveDatagramChannel.bind(new InetSocketAddress(endPointAddress.getPort()));
                receiveDatagramChannel.join(endPointAddress.getAddress(), localInterface);
                sendDatagramChannel.setOption(StandardSocketOptions.IP_MULTICAST_IF, localInterface);

                if (0 != udpChannel.multicastTtl())
                {
                    sendDatagramChannel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, udpChannel.multicastTtl());
                }

                if (null != connectAddress)
                {
                    sendDatagramChannel.connect(connectAddress);
                }
            }
            else
            {
                sendDatagramChannel.bind(bindAddress);

                if (null != connectAddress)
                {
                    sendDatagramChannel.connect(connectAddress);
                }
            }

            if (0 != Configuration.SOCKET_SNDBUF_LENGTH)
            {
                sendDatagramChannel.setOption(SO_SNDBUF, Configuration.SOCKET_SNDBUF_LENGTH);
            }

            if (0 != Configuration.SOCKET_RCVBUF_LENGTH)
            {
                receiveDatagramChannel.setOption(SO_RCVBUF, Configuration.SOCKET_RCVBUF_LENGTH);
            }

            sendDatagramChannel.configureBlocking(false);
            receiveDatagramChannel.configureBlocking(false);
        }
        catch (final IOException ex)
        {
            throw new RuntimeException(String.format("channel \"%s\" : %s", udpChannel.originalUriString(), ex), ex);
        }
    }

    /**
     * Register this transport for reading from a {@link UdpTransportPoller}.
     *
     * @param transportPoller to register read with
     */
    public void registerForRead(final UdpTransportPoller transportPoller)
    {
        this.transportPoller = transportPoller;
        selectionKey = transportPoller.registerForRead(this);
    }

    /**
     * Return underlying {@link UdpChannel}
     *
     * @return underlying channel
     */
    public UdpChannel udpChannel()
    {
        return udpChannel;
    }

    /**
     * The {@link DatagramChannel} for this transport channel.
     *
     * @return {@link DatagramChannel} for this transport channel.
     */
    public DatagramChannel receiveDatagramChannel()
    {
        return receiveDatagramChannel;
    }

    public int multicastTtl()
    {
        int result = udpChannel.multicastTtl();

        if (isMulticast())
        {
            if (0 == udpChannel.multicastTtl())
            {
                try
                {
                    result = sendDatagramChannel.getOption(IP_MULTICAST_TTL);
                }
                catch (final Exception ignore)
                {
                    // ignore
                }
            }
        }

        return result;
    }

    /**
     * Close transport, canceling any pending read operations and closing channel
     */
    public void close()
    {
        try
        {
            if (null != selectionKey)
            {
                selectionKey.cancel();
            }

            if (null != transportPoller)
            {
                transportPoller.cancelRead(this);
                transportPoller.selectNowWithoutProcessing();
            }

            if (null != sendDatagramChannel)
            {
                sendDatagramChannel.close();
            }

            if (receiveDatagramChannel != sendDatagramChannel && null != receiveDatagramChannel)
            {
                receiveDatagramChannel.close();
            }
        }
        catch (final IOException ex)
        {
            ex.printStackTrace();
            errorLog.record(ex);
        }
    }

    /**
     * Is transport representing a multicast media or unicast
     *
     * @return if transport is multicast media
     */
    public boolean isMulticast()
    {
        return udpChannel.isMulticast();
    }

    /**
     * Return socket option value
     *
     * @param name of the socket option
     * @param <T>  type of option
     * @return option value
     */
    public <T> T getOption(final SocketOption<T> name)
    {
        T option = null;
        try
        {
            option = sendDatagramChannel.getOption(name);
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return option;
    }

    /**
     * Return the capacity of the {@link ByteBuffer} used for reception
     *
     * @return capacity of receiving byte buffer
     */
    public int receiveBufferCapacity()
    {
        return receiveByteBuffer.capacity();
    }

    /**
     * Attempt to receive waiting data.
     *
     * @return number of bytes received.
     */
    public abstract int pollForData();

    public boolean isValidFrame(final UnsafeBuffer receiveBuffer, final int length)
    {
        boolean isFrameValid = true;

        if (frameVersion(receiveBuffer, 0) != HeaderFlyweight.CURRENT_VERSION)
        {
            isFrameValid = false;
        }
        else if (length < HeaderFlyweight.HEADER_LENGTH)
        {
            isFrameValid = false;
        }

        return isFrameValid;
    }

    protected InetSocketAddress receive()
    {
        receiveByteBuffer.clear();

        InetSocketAddress address = null;
        try
        {
            address = (InetSocketAddress)receiveDatagramChannel.receive(receiveByteBuffer);
        }
        catch (final PortUnreachableException | ClosedChannelException ignored)
        {
            // do nothing
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return address;
    }
}
