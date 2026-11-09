package com.makakaiglitch.fiximizer.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** Passive raw-byte observer. It never retains, modifies, reorders, or consumes messages. */
final class NetworkTrafficHandler extends ChannelDuplexHandler {
    private volatile long inboundBytes;
    private volatile long outboundBytes;
    private volatile long inboundFrames;
    private volatile long outboundFrames;

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ByteBuf buffer) {
            inboundBytes += buffer.readableBytes();
            inboundFrames++;
        }
        super.channelRead(context, message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof ByteBuf buffer) {
            outboundBytes += buffer.readableBytes();
            outboundFrames++;
        }
        super.write(context, message, promise);
    }

    long inboundBytes() { return inboundBytes; }
    long outboundBytes() { return outboundBytes; }
    long inboundFrames() { return inboundFrames; }
    long outboundFrames() { return outboundFrames; }
}
