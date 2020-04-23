package org.jgroups.blocks.cs;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.stack.IpAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/***
 * @author Baizel Mathew
 */
public class NettyClient {
    protected final Log log = LogFactory.getLog(this.getClass());

    private EventLoopGroup group;
    ChannelGroup connections;
    private Map<SocketAddress, ChannelId> channelIds;
    private Bootstrap bootstrap;

    public NettyClient(InetAddress local_addr, int port, int max_timeout_interval) {
        channelIds = new HashMap<>();
        connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

        this.group = new NioEventLoopGroup(2);


        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .localAddress(local_addr, port)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, max_timeout_interval)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ByteArrayEncoder());
                        ch.pipeline().addLast(new Decoder());
                        ch.pipeline().addLast(new ClientHandler());
                    }
                });
    }

    public NettyClient(InetAddress local_addr, int port) {
        this(local_addr, port, 2000);
    }

    public void close() throws InterruptedException {
        group.shutdownGracefully().sync();
    }

    public void send(IpAddress dest, byte[] data, int offset, int length) throws InterruptedException {
        send(dest.getIpAddress(), dest.getPort(), data, offset, length);
    }

    public void send(InetAddress remote_addr, int remote_port, byte[] data, int offset, int length) throws InterruptedException {
        InetSocketAddress dest = new InetSocketAddress(remote_addr, remote_port);
        Channel ch = connect(dest);
        if (ch != null && ch.isOpen()) {
            //TODO: Fix race condition here
            byte[] packedData = pack(data, offset, length);
            ch.writeAndFlush(packedData).sync();
        }
    }

    private byte[] pack(byte[] data, int offset, int length) {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + Integer.BYTES + data.length);
        buf.putInt(offset);
        buf.putInt(length);
        buf.put(data);
        return buf.array();
    }

    private Channel connect(InetSocketAddress remote_addr) throws InterruptedException {
        if (!channelIds.containsKey(remote_addr)) {
            ChannelFuture cf = bootstrap.connect(remote_addr);
            cf.awaitUninterruptibly(); // Wait max_timeout_interval seconds for conn

            if (cf.isDone())
                if (cf.isSuccess())
                    return cf.channel();
                else {
                    cf.channel().close().sync();
                }
            return null;
        }
        return connections.find(channelIds.get(remote_addr));
    }


    class ClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            SocketAddress addr = ctx.channel().remoteAddress();
            connections.add(ctx.channel());
            channelIds.put(addr, ctx.channel().id());
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
            log.error("Client received message when its not supposed to");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel ch = ctx.channel();
            connections.remove(ch);
            channelIds.remove(ch.remoteAddress());
            ch.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error caught in client "+ cause);
        }
    }
}
