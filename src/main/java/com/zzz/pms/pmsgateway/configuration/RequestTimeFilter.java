package com.zzz.pms.pmsgateway.configuration;

import com.zzz.pms.pmsgeneric.util.SpringUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTags;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
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
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Long startTime = exchange.getAttribute("start_time");
            String tid = exchange.getAttribute("traceId");
            if (startTime != null) {
                long executeTime = System.currentTimeMillis() - startTime;
                custmerte(exchange.getAttribute("uri"), startTime);
                log.info("finish # " + tid + " 耗时: " + executeTime + " ms");
            }
        }));
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
