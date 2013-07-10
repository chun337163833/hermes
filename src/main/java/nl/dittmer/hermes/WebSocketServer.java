package nl.dittmer.hermes;

import static org.jboss.netty.channel.Channels.pipeline;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import nl.dittmer.hermes.handler.HttpUpstreamHandler;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;

import akka.actor.ActorSystem;

public class WebSocketServer {

	static final String ActionSystemName = "INTERNAL";

	private final int port;
	private final ActorSystem system;

	public WebSocketServer(int port) {
		this.system = ActorSystem.create(ActionSystemName);
		this.port = port;
	}

	public void run() {

		ServerBootstrap bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
				Executors.newCachedThreadPool(), Executors.newCachedThreadPool()));

		bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				ChannelPipeline pipeline = pipeline();
				pipeline.addLast("decoder", new HttpRequestDecoder());
				pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
				pipeline.addLast("encoder", new HttpResponseEncoder());
				pipeline.addLast("handler", new HttpUpstreamHandler(system));
				return pipeline;
			}
		});

		bootstrap.bind(new InetSocketAddress(port));

		System.out.println("Web socket server started at port " + port + '.');
		System.out.println("Open your browser and navigate to http://localhost:" + port + '/');
	}

	public final ActorSystem getSystem() {
		return system;
	}

	public static void main(String[] args) {
		int port;
		if (args.length > 0) {
			port = Integer.parseInt(args[0]);
		} else {
			port = 8080;
		}
		new WebSocketServer(port).run();
	}
}