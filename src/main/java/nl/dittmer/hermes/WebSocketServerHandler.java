package nl.dittmer.hermes;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import nl.dittmer.hermes.actor.EchoActor;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.OnSuccess;
import akka.pattern.Patterns;
import akka.util.Timeout;

public class WebSocketServerHandler extends SimpleChannelUpstreamHandler {
	private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketServerHandler.class);

	private static final String WEBSOCKET_PATH = "/websocket";

	private WebSocketServerHandshaker handshaker;
	
	private final ActorSystem system;
	private final ActorRef channelActor;

	public WebSocketServerHandler(ActorSystem system) {
		this.system = system;
		this.channelActor = system.actorOf(new Props(EchoActor.class));	
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		Object msg = e.getMessage();
		if (msg instanceof HttpRequest) {
			handleHttpRequest(ctx, (HttpRequest) msg);
		} else if (msg instanceof WebSocketFrame) {
			handleWebSocketFrame(ctx, (WebSocketFrame) msg);
		}
	}

	private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) throws Exception {

		if (req.getMethod() != GET) {
			sendHttpResponse(ctx, req, new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
			return;
		}

		if ("/".equals(req.getUri())) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, OK);

			ChannelBuffer content = WebSocketServerIndexPage.getContent(getWebSocketLocation(req));

			res.setHeader(CONTENT_TYPE, "text/html; charset=UTF-8");
			setContentLength(res, content.readableBytes());

			res.setContent(content);
			sendHttpResponse(ctx, req, res);
			return;
		}

		if ("/favicon.ico".equals(req.getUri())) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
			sendHttpResponse(ctx, req, res);
			return;
		}

		WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req),
				null, false);

		handshaker = wsFactory.newHandshaker(req);

		if (handshaker == null) {
			wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
		} else {
			handshaker.handshake(ctx.getChannel(), req).addListener(WebSocketServerHandshaker.HANDSHAKE_LISTENER);
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

	private static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
		if (res.getStatus().getCode() != 200) {
			res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8));
			setContentLength(res, res.getContent().readableBytes());
		}

		ChannelFuture f = ctx.getChannel().write(res);
		if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
			f.addListener(ChannelFutureListener.CLOSE);
		}
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

	private static String getWebSocketLocation(HttpRequest req) {
		return "ws://" + req.getHeader(HOST) + WEBSOCKET_PATH;
	}

}