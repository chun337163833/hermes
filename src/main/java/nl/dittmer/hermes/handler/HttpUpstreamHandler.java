package nl.dittmer.hermes.handler;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import nl.dittmer.hermes.WebSocketServerIndexPage;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import akka.actor.ActorSystem;

public class HttpUpstreamHandler extends SimpleChannelUpstreamHandler {
	private static final InternalLogger logger = InternalLoggerFactory.getInstance(HttpUpstreamHandler.class);

	private WebSocketServerHandshaker handshaker;
	private static final String WEBSOCKET_PATH = "/websocket";

	private final ActorSystem system;

	public HttpUpstreamHandler(ActorSystem system) {
		this.system = system;
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		Object msg = e.getMessage();
		if (msg instanceof HttpRequest) {
			handleHttpRequest(ctx, (HttpRequest) msg);
		} else {
			ctx.getChannel().close();
		}
	}

	private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) throws Exception {

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
		
		/*
		 * Upgrade connection
		 */
		if(req.getMethod() == GET && req.getHeader("Connection").equals("Upgrade")) {
			
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Connection %s received %s", ctx.getChannel().getId(), "Upgrade"));
			}			
			
			WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req),
					null, false);

			handshaker = wsFactory.newHandshaker(req);

			if (handshaker == null) {
				wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
			} else {
				final SimpleChannelUpstreamHandler handler = this;
				
				handshaker.handshake(ctx.getChannel(), req).addListener(new ChannelFutureListener() {
			        public void operationComplete(ChannelFuture future) throws Exception {
			            if (future.isSuccess()) {
			            	future.getChannel().getPipeline().replace(handler, "websocket", new WebSocketUpstreamHandler(system));
			            } else {
			            	Channels.fireExceptionCaught(future.getChannel(), future.getCause());
			            }
			        }
				});
			}
			
			return;
		}
		
		sendHttpResponse(ctx, req, new DefaultHttpResponse(HTTP_1_1, NOT_FOUND));
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

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		e.getCause().printStackTrace();
		e.getChannel().close();
	}

	private static String getWebSocketLocation(HttpRequest req) {
		return "ws://" + req.getHeader(HOST) + WEBSOCKET_PATH;
	}
}
