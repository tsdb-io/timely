package timely.server.netty.websocket.subscription;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import timely.api.RemoveSubscription;
import timely.subscription.Subscription;
import timely.subscription.SubscriptionRegistry;

public class WSRemoveSubscriptionRequestHandler extends SimpleChannelInboundHandler<RemoveSubscription> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RemoveSubscription remove) throws Exception {
        Subscription s = SubscriptionRegistry.get().get(remove.getSubscriptionId());
        if (null != s) {
            s.removeMetric(remove.getMetric());
        }
    }

}
