package nl.dittmer.hermes.event;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;

public class ChannelEvent {
	
	public final Channel channel;
	public final WebSocketFrame frame;

	public ChannelEvent(Channel channel, WebSocketFrame frame) {
		this.channel = channel;
		this.frame = frame;
	}
}
