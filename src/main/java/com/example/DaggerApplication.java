package com.example;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.inject.Qualifier;
import javax.inject.Singleton;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;

import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public class DaggerApplication implements Function<String, String> {

	public static void main(String[] args) {
		DaggerMain.builder().function(new DaggerApplication()).args(args).build().server()
				.run();
	}

	@Override
	public String apply(String t) {
		return t.toUpperCase();
	}
}

@Component(modules = FunctionEndpointFactory.class)
@Singleton
interface Main {
	@Server
	Runnable server();

	@PortNumber
	int port();

	@Component.Builder
	interface Builder {
		Main build();

		Builder functionEndpointFactory(FunctionEndpointFactory endpoints);

		Builder environmentFactory(EnvironmentFactory endpoints);

		@BindsInstance
		Builder function(Function<String, String> function);

		@BindsInstance
		Builder args(@CommandLine String... args);
	}

}

@Qualifier
@Documented
@Retention(RUNTIME)
@interface PortNumber {
	String value() default "local-server-port";
}

@Qualifier
@Documented
@Retention(RUNTIME)
@interface CommandLine {
	String value() default "command-line";
}

@Qualifier
@Documented
@Retention(RUNTIME)
@interface Server {
	String value() default "server";
}

@Module(includes = NettyServerFactory.class)
class FunctionEndpointFactory {

	@Provides
	@Singleton
	public RouterFunction<?> functionEndpoints(Function<String, String> function) {
		return route(POST("/"), request -> ok()
				.body(request.bodyToMono(String.class).map(function), String.class));
	}

}

@Module(includes = EnvironmentFactory.class)
class NettyServerFactory {

	private static Log logger = LogFactory.getLog(NettyServerFactory.class);
	private volatile int port;
	private CountDownLatch latch = new CountDownLatch(1);

	@Provides
	@Singleton
	@Server
	public Runnable handler(ConfigurableEnvironment environment,
			HttpWebHandlerAdapter handler) {
		return () -> {
			Integer port = Integer.valueOf(
					environment.resolvePlaceholders("${server.port:${PORT:8080}}"));
			String address = environment.resolvePlaceholders("${server.address:0.0.0.0}");
			if (port >= 0) {
				ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(
						handler);
				HttpServer httpServer = HttpServer.create().host(address).port(port)
						.handle(adapter);
				Thread thread = new Thread(() -> httpServer
						.bindUntilJavaShutdown(Duration.ofSeconds(60), this::callback),
						"server-startup");
				thread.setDaemon(false);
				thread.start();
			}
			else {
				logger.info("No server to run (port=" + port + ")");
				this.port = port;
				this.latch.countDown();
			}
		};
	}

	@Provides
	@Singleton
	@PortNumber
	int port() {
		try {
			latch.await(100, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return this.port;
	}

	@Provides
	@Singleton
	public HttpWebHandlerAdapter httpHandler(RouterFunction<?> router) {
		return (HttpWebHandlerAdapter) RouterFunctions.toHttpHandler(router,
				HandlerStrategies.empty().codecs(config -> config.registerDefaults(true))
						.build());
	}

	private void callback(DisposableServer server) {
		this.port = server.port();
		this.latch.countDown();
		logger.info("Server started on port=" + server.port());
		try {
			double uptime = ManagementFactory.getRuntimeMXBean().getUptime();
			logger.info("JVM running for " + uptime + "ms");
		}
		catch (Throwable e) {
			// ignore
		}
	}
}

@Module
class EnvironmentFactory {

	@Provides
	@Singleton
	public Optional<CommandLinePropertySource<?>> properties(
			@CommandLine String... args) {
		if (args != null && args.length > 0) {
			return Optional.of(new SimpleCommandLinePropertySource(args));
		}
		return Optional.empty();
	}

	@Provides
	@Singleton
	public ConfigurableEnvironment environment(
			Optional<CommandLinePropertySource<?>> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		properties.ifPresent(source -> environment.getPropertySources().addFirst(source));
		return environment;
	}
}