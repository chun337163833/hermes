package nl.dittmer.hermes.handler;

import nl.dittmer.hermes.actor.EchoActor;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.OnSuccess;
import akka.pattern.Patterns;
import akka.util.Timeout;

public class WebSocketUpstreamHandler extends SimpleChannelUpstreamHandler {
	private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketUpstreamHandler.class);

	private WebSocketServerHandshaker handshaker;

	private final ActorSystem system;
	private final ActorRef channelActor;

	public WebSocketUpstreamHandler(ActorSystem system) {

		this.system = system;
		this.channelActor = system.actorOf(new Props(EchoActor.class));
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		Object msg = e.getMessage();

		if (msg instanceof WebSocketFrame) {
			handleWebSocketFrame(ctx, (WebSocketFrame) msg);
		} else {
			ctx.getChannel().close();
		}
	}

	private void handleWebSocketFrame(final ChannelHandlerContext ctx, WebSocketFrame frame) {

		if (frame instanceof CloseWebSocketFrame) {
			handshaker.close(ctx.getChannel(), (CloseWebSocketFrame) frame);
			return;
		}
		
		if (frame instanceof PingWebSocketFrame) {
			ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
			return;
		}

		if (!(frame instanceof TextWebSocketFrame)) {
			throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
					.getName()));
		}

		String request = ((TextWebSocketFrame) frame).getText();

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Channel %s received %s", ctx.getChannel().getId(), request));
		}

		Timeout timeout = new Timeout(Duration.create(30, "seconds"));
		Future<Object> f = Patterns.ask(channelActor, frame, timeout);
		f.onSuccess(new FrameResultWriter<Object>(ctx.getChannel()), system.dispatcher());
	}

	public final static class FrameResultWriter<T> extends OnSuccess<T> {
		final Channel channel;

		public FrameResultWriter(final Channel channel) {
			this.channel = channel;
		}

		@Override
		public final void onSuccess(T t) {
			channel.write(new TextWebSocketFrame((String) t));
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		e.getCause().printStackTrace();
		e.getChannel().close();
	}
}