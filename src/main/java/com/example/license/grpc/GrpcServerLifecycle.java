package com.example.license.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the Netty gRPC server, integrating with Spring's SmartLifecycle
 * so the server starts after the application context is fully initialized and shuts down gracefully.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private static final int MAX_INBOUND_MESSAGE_BYTES = 4 * 1024 * 1024;

    private final ExtAuthzService extAuthzService;
    private final int grpcPort;
    private final boolean enableReflection;

    private Server server;
    private volatile boolean running = false;

    /** 注入 ext_authz 服务实现和配置属性，从属性中读取端口及反射开关。 */
    public GrpcServerLifecycle(ExtAuthzService extAuthzService,
                                com.example.license.config.LicenseProperties properties) {
        this.extAuthzService = extAuthzService;
        this.grpcPort = properties.getGrpcPort();
        this.enableReflection = properties.isGrpcReflection();
    }

    /** 构建并启动 Netty gRPC 服务器；线程数为 CPU 核心数的两倍。 */
    @Override
    public void start() {
        int workers = Runtime.getRuntime().availableProcessors() * 2;
        NettyServerBuilder builder = NettyServerBuilder.forPort(grpcPort)
                .addService(extAuthzService)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS);

        if (enableReflection) {
            builder.addService(ProtoReflectionService.newInstance());
        }

        try {
            server = builder.build().start();
            running = true;
            log.info("gRPC server started on port {}", grpcPort);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + grpcPort, e);
        }
    }

    /** 优雅关闭 gRPC 服务器，等待最多 10 秒完成处理中的请求。 */
    @Override
    public void stop() {
        running = false;
        if (server != null) {
            log.info("Shutting down gRPC server");
            server.shutdown();
            try {
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 返回服务器是否处于运行状态。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** 返回 SmartLifecycle 启动阶段编号，确保在所有业务 Bean 初始化完成后再启动服务器。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
