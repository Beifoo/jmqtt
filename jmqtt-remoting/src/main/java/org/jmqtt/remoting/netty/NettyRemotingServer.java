package org.jmqtt.remoting.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.timeout.IdleStateHandler;
import org.jmqtt.common.config.NettyConfig;
import org.jmqtt.common.helper.MixAll;
import org.jmqtt.common.helper.Pair;
import org.jmqtt.common.helper.ThreadFactoryImpl;
import org.jmqtt.common.log.LoggerName;
import org.jmqtt.remoting.RemotingServer;
import org.jmqtt.remoting.netty.codec.ByteBuf2WebSocketEncoder;
import org.jmqtt.remoting.netty.codec.WebSocket2ByteBufDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;


public class NettyRemotingServer implements RemotingServer {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.REMOTING);
    private NettyConfig nettyConfig;
    private EventLoopGroup selectorGroup;
    private EventLoopGroup ioGroup;
    private Class<? extends ServerChannel> clazz;
    private Map<MqttMessageType, Pair<RequestProcessor, ExecutorService>> processorTable;
    private NettyEventExcutor nettyEventExcutor;

    public NettyRemotingServer(NettyConfig nettyConfig,ChannelEventListener listener) {
        this.nettyConfig = nettyConfig;
        this.processorTable = new HashMap();
        this.nettyEventExcutor = new NettyEventExcutor(listener);

        if(!nettyConfig.isUseEpoll()){
            selectorGroup = new NioEventLoopGroup(nettyConfig.getSelectorThreadNum(),
                    new ThreadFactoryImpl("SelectorEventGroup"));
            ioGroup = new NioEventLoopGroup(nettyConfig.getIoThreadNum(),
                    new ThreadFactoryImpl("IOEventGroup"));
            clazz = NioServerSocketChannel.class;
        }else{
            selectorGroup = new EpollEventLoopGroup(nettyConfig.getSelectorThreadNum(),
                    new ThreadFactoryImpl("SelectorEventGroup"));
            ioGroup = new EpollEventLoopGroup(nettyConfig.getIoThreadNum(),
                    new ThreadFactoryImpl("IOEventGroup"));
            clazz = EpollServerSocketChannel.class;
        }
    }


    @Override
    public void start() {
        //Netty event excutor start
        this.nettyEventExcutor.start();
        // start TCP 1883 server
        startTcpServer();
        // start Websocket server
        if(nettyConfig.isStartWebsocket()){
            startWebsocketServer();
        }
    }

    private void startWebsocketServer(){
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(selectorGroup,ioGroup)
                .channel(clazz)
                .option(ChannelOption.SO_BACKLOG, nettyConfig.getTcpBackLog())
                .childOption(ChannelOption.TCP_NODELAY, nettyConfig.isTcpNoDelay())
                .childOption(ChannelOption.SO_SNDBUF, nettyConfig.getTcpSndBuf())
                .option(ChannelOption.SO_RCVBUF, nettyConfig.getTcpRcvBuf())
                .option(ChannelOption.SO_REUSEADDR, nettyConfig.isTcpReuseAddr())
                .childOption(ChannelOption.SO_KEEPALIVE, nettyConfig.isTcpKeepAlive())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("idleStateHandler", new IdleStateHandler(0, 0, 60))
                                .addLast("nettyConnectionManager", new NettyConnectHandler(nettyEventExcutor))
                                .addLast("httpCodec",new HttpServerCodec())
                                .addLast("aggregator",new HttpObjectAggregator(65535))
                                .addLast("webSocketHandler",new WebSocketServerProtocolHandler("/mqtt", MixAll.MQTT_VERSION_SUPPORT))
                                .addLast("webSocket2ByteBufDecoder",new WebSocket2ByteBufDecoder())
                                .addLast("byteBuf2WebSocketEncoder",new ByteBuf2WebSocketEncoder())
                                .addLast("mqttEncoder", MqttEncoder.INSTANCE)
                                .addLast("mqttDecoder", new MqttDecoder(nettyConfig.getMaxMsgSize()))
                                .addLast("nettyMqttHandler", new NettyMqttHandler());
                    }
                });
        if(nettyConfig.isPooledByteBufAllocatorEnable()){
            bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }
        try {
            ChannelFuture future = bootstrap.bind(nettyConfig.getWebsocketPort()).sync();
            log.info("Start webSocket server success,port = {}",nettyConfig.getWebsocketPort());
        }catch (InterruptedException ex){
            log.error("Start webSocket server failure.cause={}",ex);
        }
    }

    private void startTcpServer(){
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(selectorGroup,ioGroup)
                .channel(clazz)
                .option(ChannelOption.SO_BACKLOG, nettyConfig.getTcpBackLog())
                .childOption(ChannelOption.TCP_NODELAY, nettyConfig.isTcpNoDelay())
                .childOption(ChannelOption.SO_SNDBUF, nettyConfig.getTcpSndBuf())
                .option(ChannelOption.SO_RCVBUF, nettyConfig.getTcpRcvBuf())
                .option(ChannelOption.SO_REUSEADDR, nettyConfig.isTcpReuseAddr())
                .childOption(ChannelOption.SO_KEEPALIVE, nettyConfig.isTcpKeepAlive())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("idleStateHandler", new IdleStateHandler(60, 0, 0))
                                .addLast("mqttEncoder", MqttEncoder.INSTANCE)
                                .addLast("mqttDecoder", new MqttDecoder(nettyConfig.getMaxMsgSize()))
                                .addLast("nettyConnectionManager", new NettyConnectHandler(nettyEventExcutor))
                                .addLast("nettyMqttHandler", new NettyMqttHandler());
                    }
                });
        if(nettyConfig.isPooledByteBufAllocatorEnable()){
            bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }
        try {
            ChannelFuture future = bootstrap.bind(nettyConfig.getTcpPort()).sync();
            log.info("Start tcp server success,port = {}",nettyConfig.getTcpPort());
        }catch (InterruptedException ex){
            log.error("Start tcp server failure.cause={}",ex);
        }
    }



    @Override
    public void shutdown() {
        if (selectorGroup != null) {
            selectorGroup.shutdownGracefully();
        }
        if (ioGroup != null) {
            ioGroup.shutdownGracefully();
        }
    }

    public void registerProcessor(MqttMessageType mqttType,RequestProcessor processor,ExecutorService executorService){
        this.processorTable.put(mqttType,new Pair<>(processor,executorService));
    }

    class NettyMqttHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object obj){
            MqttMessage mqttMessage = (MqttMessage) obj;
            if(mqttMessage != null && mqttMessage.decoderResult().isSuccess()){
                MqttMessageType messageType = mqttMessage.fixedHeader().messageType();
                log.debug("[Remoting] -> receive mqtt code,type:{}",messageType.value());
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        processorTable.get(messageType).getObject1().processRequest(ctx,mqttMessage);
                    }
                };
                try{
                    processorTable.get(messageType).getObject2().submit(runnable);
                }catch (RejectedExecutionException ex){
                    log.warn("Reject mqtt request,cause={}",ex.getMessage());
                }
            }else{
                ctx.close();
            }
        }

    }

}
