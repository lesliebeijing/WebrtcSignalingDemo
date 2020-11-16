package com.lesliefang.webrtc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    public static ConcurrentHashMap<String, Channel> userMap = new ConcurrentHashMap<>();
    private AttributeKey<String> userIdKey = AttributeKey.valueOf("userId");

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        logger.info("channelActive {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("channelInactive {}", ctx.channel().remoteAddress());
        String userId = ctx.channel().attr(userIdKey).get();
        if (userId != null) {
            ctx.channel().attr(userIdKey).set(null);
            userMap.remove(userId);
            logger.info("user {} 下线", userId);
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) throws Exception {
        JSONObject jsonObject = JSON.parseObject(msg.text());
        String event = jsonObject.getString("event");
        if ("register".equals(event)) {
            // 注册
            String userId = jsonObject.getString("userId");
            if (!userMap.containsKey(userId)) {
                ctx.channel().attr(userIdKey).set(userId);
                userMap.put(userId, ctx.channel());
                logger.info("user {} 上线", userId);
            }
        } else if ("sdp".equals(event) || "trickle".equals(event)) {
            // sdp 和 ICE trickle 消息根据接收者直接转发给对方
            logger.info("收到消息 {} {}->{} <<== {}",
                    event,
                    jsonObject.getString("sender"),
                    jsonObject.getString("receiver"),
                    jsonObject.toString());
            String receiver = jsonObject.getString("receiver");
            if (receiver != null && userMap.containsKey(receiver)) {
                msg.retain();
                userMap.get(receiver).writeAndFlush(msg);
                logger.info("转发消息 {} {}->{} ==>> {}",
                        event,
                        jsonObject.getString("sender"),
                        jsonObject.getString("receiver"),
                        jsonObject.toString());
            }
        }
    }
}
