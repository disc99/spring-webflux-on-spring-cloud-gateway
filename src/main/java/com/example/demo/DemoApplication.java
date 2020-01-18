package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.function.server.support.RouterFunctionMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
@Slf4j
public class DemoApplication {
	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder, @Value("${server.port:8080}") String port) {
		return builder.routes()

				.route("route0", r -> r.host("0.0.0.0*")
						.filters(f -> f.filter((e, c) -> {
							log.info("route0");
							return c.filter(e);
						}))
						.uri("https://httpstat.us"))

				.route("route1", r -> r.host("localhost*")
						.and().predicate(SelfForwardRoutingFilter::isBeforeForward)
						.filters(f -> f.filter((e, c) -> {
							log.info("route1");
							return c.filter(e);
						}))
						.uri("http://localhost:" + port)
				)

				.route("route2", r -> r.host("127.0.0.1*")
						.filters(f -> f.filter((e, c) -> {
							log.info("route2");
							return c.filter(e);
						}))
						.uri("http://httpstat.us"))

				.build();
	}

	@Bean
	RouterFunction<ServerResponse> route() {
		return RouterFunctions.route()
				.GET("/echo", this::getEcho)
				.build();
	}

	private Mono<ServerResponse> getEcho(ServerRequest serverRequest) {
		return ok().bodyValue("Hello!");
	}

	@Bean
	public SelfForwardRoutingFilter selfForwardRoutingFilter(ObjectProvider<DispatcherHandler> dispatcherHandlerProvider,
									 @Value("${server.port:8080}") int port) {
		return new SelfForwardRoutingFilter(dispatcherHandlerProvider, port);
	}

	/**
	 * Filter for performing Gateway routing to itself.
	 *
	 * <p>
	 *     If the gatewayRequestUrl of the attribute of {@link ServerWebExchange} is itself, re-call DispatcherHandler.
	 *     This process enables remapping to {@link RouterFunctionMapping}.
	 *     This Filter must also call {@link SelfForwardRoutingFilter#isBeforeForward} with {@link RouteLocator}.
	 *     Also, {@link org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping RoutePredicateHandlerMapping} needs to be executed before {@link RouterFunctionMapping}.
	 * </p>
	 */
	public static class SelfForwardRoutingFilter implements GlobalFilter, Ordered {

		private final ObjectProvider<DispatcherHandler> dispatcherHandlerProvider;
		private volatile DispatcherHandler dispatcherHandler;
		private final int port;
		private static final  String SELF_FORWARDED = SelfForwardRoutingFilter.class.getName() + ".isSelfForwarded";

		public SelfForwardRoutingFilter(ObjectProvider<DispatcherHandler> dispatcherHandlerProvider, int port) {
			this.dispatcherHandlerProvider = dispatcherHandlerProvider;
			this.port = port;
		}

		private DispatcherHandler getDispatcherHandler() {
			if (dispatcherHandler == null) {
				dispatcherHandler = dispatcherHandlerProvider.getIfAvailable();
			}
			return dispatcherHandler;
		}

		// Since it needs to be applied before NettyRoutingFilter, it returns the value of that hassle.
		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE - 1;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
			if (requestUrl.getHost().equals("localhost") && requestUrl.getPort() == port) {
				exchange.getAttributes().put(SELF_FORWARDED, true);
				return this.getDispatcherHandler().handle(exchange);
			}
			return chain.filter(exchange);
		}

		/**
		 * Before this filter is forwarded.
		 * @param exchange the current server exchange
		 * @return {@code false} if not forwarded by this filter, {@code true} otherwise.
		 */
		public static boolean isBeforeForward(ServerWebExchange exchange) {
			return !exchange.getAttributeOrDefault(SELF_FORWARDED, false);
		}
	}

	@Configuration
	public static class WebConfig extends DelegatingWebFluxConfiguration {
		@Override
		protected RouterFunctionMapping createRouterFunctionMapping() {
			return new GatewayRouterFunctionMapping();
		}
	}

	static class GatewayRouterFunctionMapping extends RouterFunctionMapping {
		// Since the Order of RoutePredicateHandlerMapping which is HandlerMapping of Spring Cloud Gateway is 1,
		// it is necessary to lower the priority than that.
		@Override
		public int getOrder() {
			return 2;
		}
	}
}
