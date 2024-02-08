// automatically generated by the FlatBuffers compiler, do not modify

package timely.api.flatbuffer;

import com.google.flatbuffers.BaseVector;
import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Metric extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Metric getRootAsMetric(ByteBuffer _bb) { return getRootAsMetric(_bb, new Metric()); }
  public static Metric getRootAsMetric(ByteBuffer _bb, Metric obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Metric __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public String name() { int o = __offset(4); return o != 0 ? __string(o + bb_pos) : null; }
  public ByteBuffer nameAsByteBuffer() { return __vector_as_bytebuffer(4, 1); }
  public ByteBuffer nameInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 4, 1); }
  public long timestamp() { int o = __offset(6); return o != 0 ? bb.getLong(o + bb_pos) : 0L; }
  public double value() { int o = __offset(8); return o != 0 ? bb.getDouble(o + bb_pos) : 0.0; }
  public timely.api.flatbuffer.Tag tags(int j) { return tags(new timely.api.flatbuffer.Tag(), j); }
  public timely.api.flatbuffer.Tag tags(timely.api.flatbuffer.Tag obj, int j) { int o = __offset(10); return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null; }
  public int tagsLength() { int o = __offset(10); return o != 0 ? __vector_len(o) : 0; }
  public timely.api.flatbuffer.Tag.Vector tagsVector() { return tagsVector(new timely.api.flatbuffer.Tag.Vector()); }
  public timely.api.flatbuffer.Tag.Vector tagsVector(timely.api.flatbuffer.Tag.Vector obj) { int o = __offset(10); return o != 0 ? obj.__assign(__vector(o), 4, bb) : null; }

  public static int createMetric(FlatBufferBuilder builder,
      int nameOffset,
      long timestamp,
      double value,
      int tagsOffset) {
    builder.startTable(4);
    Metric.addValue(builder, value);
    Metric.addTimestamp(builder, timestamp);
    Metric.addTags(builder, tagsOffset);
    Metric.addName(builder, nameOffset);
    return Metric.endMetric(builder);
  }

  public static void startMetric(FlatBufferBuilder builder) { builder.startTable(4); }
  public static void addName(FlatBufferBuilder builder, int nameOffset) { builder.addOffset(0, nameOffset, 0); }
  public static void addTimestamp(FlatBufferBuilder builder, long timestamp) { builder.addLong(1, timestamp, 0L); }
  public static void addValue(FlatBufferBuilder builder, double value) { builder.addDouble(2, value, 0.0); }
  public static void addTags(FlatBufferBuilder builder, int tagsOffset) { builder.addOffset(3, tagsOffset, 0); }
  public static int createTagsVector(FlatBufferBuilder builder, int[] data) { builder.startVector(4, data.length, 4); for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]); return builder.endVector(); }
  public static void startTagsVector(FlatBufferBuilder builder, int numElems) { builder.startVector(4, numElems, 4); }
  public static int endMetric(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) { __reset(_vector, _element_size, _bb); return this; }

    public Metric get(int j) { return get(new Metric(), j); }
    public Metric get(Metric obj, int j) {  return obj.__assign(__indirect(__element(j), bb), bb); }
  }
}

