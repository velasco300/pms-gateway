package com.zzz.pms.gateway.configuration;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.alibaba.nacos.shaded.com.google.common.base.Charsets;
import com.zzz.pms.generic.util.SpringUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTags;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class RequestTimeFilter implements GlobalFilter, Ordered {

    @Bean
    public WebFluxTagsProvider webFluxTagsProvider() {
        return new WebFluxTagsProvider() {
            @Override
            public Iterable<Tag> httpRequestTags(ServerWebExchange exchange, Throwable ex) {
                Tag uri = Tag.of("uri", exchange.getRequest().getURI().getPath());
                return Arrays.asList(WebFluxTags.method(exchange), uri, WebFluxTags.exception(ex), WebFluxTags.status(exchange), WebFluxTags.outcome(exchange, ex));
            }
        };
    }

    private MeterRegistry registry;

    private void registMeter() {
        if (registry == null) {
            registry = SpringUtils.getBean(MeterRegistry.class);
        }
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String url = exchange.getRequest().getURI().getRawPath();
        String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS"));
        String traceId = "#" + method + ":" + url + ts + "#";
        log.info("starting " + traceId);
        exchange.getAttributes().put("start_time", System.currentTimeMillis());
        exchange.getAttributes().put("uri", url);
        exchange.getAttributes().put("traceId", traceId);
        exchange.getRequest().mutate().header("x-traceId", traceId).build();
        String token = exchange.getRequest().getQueryParams().getFirst("token");
        if (token != null) {
            log.info("没有权限!!!");
            return exchange.getResponse().setComplete();
        }
        ServerHttpResponseDecorator decoratedResponse = getServerHttpResponseDecorator(exchange);

        return chain.filter(exchange.mutate().response(decoratedResponse).build()).then(Mono.fromRunnable(() -> {
            Long startTime = exchange.getAttribute("start_time");
            String tid = exchange.getAttribute("traceId");
            if (startTime != null) {
                long executeTime = System.currentTimeMillis() - startTime;
                custmerte(exchange.getAttribute("uri"), startTime);
                log.info("finish # " + tid + " 耗时: " + executeTime + " ms");
            }
        }));
    }

    private ServerHttpResponseDecorator getServerHttpResponseDecorator(ServerWebExchange exchange) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();
        return new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                    return super.writeWith(fluxBody.buffer().map(dataBuffers -> {
                        DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
                        DataBuffer join = dataBufferFactory.join(dataBuffers);
                        byte[] content = new byte[join.readableByteCount()];
                        join.read(content);
                        // 释放掉内存
                        DataBufferUtils.release(join);
                        String responseData = new String(content, Charsets.UTF_8);
                        if (StringUtils.isNotBlank(responseData) && originalResponse.getHeaders().getFirst("Content-Type").contains("application/json")) {
                            log.info("对数据进行处理" + responseData);
                        }
                        byte[] uppedContent = responseData.getBytes(Charsets.UTF_8);
                        return bufferFactory.wrap(uppedContent);
                    }));

                }
                return super.writeWith(body);
            }
        };
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private void custmerte(String uri, long start) {
        registMeter();
        registry.timer("my_slomotion_api_request", "uri", uri).record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
    }
}
