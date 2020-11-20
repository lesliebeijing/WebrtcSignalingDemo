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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    public static ConcurrentHashMap<String, Channel> userMap = new ConcurrentHashMap<>();
    private AttributeKey<String> userIdKey = AttributeKey.valueOf("userId");
    // 默认就一个房间，所有人都在一个房间内
    public static Set<String> roomSet = Collections.synchronizedSet(new HashSet<>());

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
            roomSet.remove(userId);
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
            } else {
                logger.info("user{}已经上线");
            }
        } else if ("sdp".equals(event) || "trickle".equals(event)) {
            // sdp 和 ICE trickle 消息根据接收者直接转发给对方
            logger.info("收到消息 {} {}->{} <<== {}",
                    event,
                    jsonObject.getString("sender"),
                    jsonObject.getString("receiver"),
                    jsonObject.toString());
            String receiver = jsonObject.getString("receiver");
            if (!roomSet.contains(receiver)) {
                logger.error("接收者{}没有加入房间", receiver);
                return;
            }
            if (receiver != null && userMap.containsKey(receiver)) {
                msg.retain();
                userMap.get(receiver).writeAndFlush(msg);
                logger.info("转发消息 {} {}->{} ==>> {}",
                        event,
                        jsonObject.getString("sender"),
                        jsonObject.getString("receiver"),
                        jsonObject.toString());
            }
        } else if ("joinRoom".equals(event)) {
            String userId = ctx.channel().attr(userIdKey).get();
            if (userId == null) {
                logger.error("该用户未注册，请先注册");
                return;
            }
            roomSet.add(userId);
            logger.info("用户{}加入房间", userId);
            for (String uid : roomSet) {
                if (!userId.equals(uid) && userMap.containsKey(uid)) {
                    // 向房间内的其他人转发新加入房间的消息，附加新加入房间的用户ID
                    JSONObject joinRoomObj = new JSONObject();
                    joinRoomObj.put("event", "joinRoom");
                    joinRoomObj.put("userId", userId);
                    userMap.get(uid).writeAndFlush(new TextWebSocketFrame(joinRoomObj.toJSONString()));
                }
            }
        } else if ("leaveRoom".equals(event)) {
            String userId = ctx.channel().attr(userIdKey).get();
            if (userId == null) {
                logger.error("该用户未注册，请先注册");
                return;
            }
            roomSet.remove(userId);
            logger.info("用户{}离开房间", userId);
            for (String uid : roomSet) {
                if (userMap.containsKey(uid)) {
                    // 向房间内的其他人转发新离开房间的消息
                    JSONObject leaveRoomObj = new JSONObject();
                    leaveRoomObj.put("event", "leaveRoom");
                    leaveRoomObj.put("userId", userId);
                    userMap.get(uid).writeAndFlush(new TextWebSocketFrame(leaveRoomObj.toJSONString()));
                }
            }
        }
    }
}
