package com.hmall.gateway.filters;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 *
 * demo类，不用管
 *
 */
@Component
public class MyGlobalFilter implements GlobalFilter, Ordered {
    /**
     * 重写GlobalFilter的Mono方法
     * @param exchange
     * @param chain
     * @return
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 模拟登录校验逻辑
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        System.out.println("headers = " + headers);
        //放行
        return chain.filter(exchange);
    }


    /**
     * 重写Ordered中的getOrder方法，设置更高的优先级，会优先执行此实现类，Ordered为spring中用来排序的接口
     * @return
     */
    @Override
    public int getOrder() {
        return 0;   //数值越小优先级越高，比Ordered的getOrder中的MAX_VALUE = 2147483647小即可
    }
}
