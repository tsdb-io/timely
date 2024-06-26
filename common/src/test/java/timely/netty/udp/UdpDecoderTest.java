package timely.netty.udp;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import timely.accumulo.MetricAdapter;
import timely.api.request.MetricRequest;
import timely.model.Metric;
import timely.model.Tag;

public class UdpDecoderTest {

    private static final Long TEST_TIME = (System.currentTimeMillis() / 1000) * 1000;

    @Test
    public void testPutNoViz() throws Exception {
        UdpDecoder decoder = new UdpDecoder();
        List<Object> results = new ArrayList<>();
        String put = "put sys.cpu.user " + TEST_TIME + " 1.0 tag1=value1 tag2=value2";
        ByteBuf buf = Unpooled.wrappedBuffer(put.getBytes());
        decoder.decode(null, buf, results);
        Assert.assertEquals(1, results.size());
        Assert.assertEquals(MetricRequest.class, results.get(0).getClass());
        Metric m = ((MetricRequest) results.get(0)).getMetric();
        Metric expected = Metric.newBuilder().name("sys.cpu.user").value(TEST_TIME, 1.0D).tag(new Tag("tag1", "value1")).tag(new Tag("tag2", "value2")).build();
        Assert.assertEquals(expected, m);
    }

    @Test
    public void testPutWithViz() throws Exception {
        UdpDecoder decoder = new UdpDecoder();
        List<Object> results = new ArrayList<>();
        String put = "put sys.cpu.user " + TEST_TIME + " 1.0 tag1=value1 viz=a&b tag2=value2";
        ByteBuf buf = Unpooled.wrappedBuffer(put.getBytes());
        decoder.decode(null, buf, results);
        Assert.assertEquals(1, results.size());
        Assert.assertEquals(MetricRequest.class, results.get(0).getClass());
        Metric m = ((MetricRequest) results.get(0)).getMetric();
        Metric expected = Metric.newBuilder().name("sys.cpu.user").value(TEST_TIME, 1.0D).tag(new Tag("tag1", "value1")).tag(new Tag("tag2", "value2"))
                        .tag(MetricAdapter.VISIBILITY_TAG, "a&b").build();
        Assert.assertEquals(expected, m);
    }

    @Test
    public void testUnknownOperation() throws Exception {
        UdpDecoder decoder = new UdpDecoder();
        List<Object> results = new ArrayList<>();
        String put = "version";
        ByteBuf buf = Unpooled.wrappedBuffer(put.getBytes());
        decoder.decode(null, buf, results);
        Assert.assertEquals(0, results.size());
    }

}
