package io.stargate.grpc.codec.cql;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.stargate.db.schema.Column.ColumnType;
import io.stargate.proto.QueryOuterClass.Collection;
import io.stargate.proto.QueryOuterClass.Value;
import java.nio.ByteBuffer;

public class MapCodec extends CompositeCodec {

  @Override
  public ByteBuffer encode(@NonNull Value value, @NonNull ColumnType type) {
    if (!value.hasCollection()) {
      throw new IllegalArgumentException("Expected collection type");
    }
    assert type.isMap();

    Collection map = value.getCollection();

    ColumnType keyType = type.parameters().get(0);
    ValueCodec keyCodec = ValueCodecs.get(keyType.rawType());

    ColumnType valueType = type.parameters().get(1);
    ValueCodec valueCodec = ValueCodecs.get(valueType.rawType());

    int elementCount = map.getElementsCount();
    if (elementCount % 2 != 0) {
      throw new IllegalArgumentException("Expected an even number of elements");
    }

    ByteBuffer[] encodedElements = new ByteBuffer[elementCount];
    int toAllocate = 4;
    for (int i = 0; i < elementCount; i += 2) {
      ByteBuffer encodedKey = ValueCodec.encodeValue(keyCodec, map.getElements(i), keyType);
      checkElementForNull(encodedKey);
      encodedElements[i] = encodedKey;
      toAllocate += 4 + encodedKey.remaining();
      ByteBuffer encodedValue =
          ValueCodec.encodeValue(valueCodec, map.getElements(i + 1), valueType);
      checkElementForNull(encodedValue);
      encodedElements[i + 1] = encodedValue;
      toAllocate += 4 + encodedValue.remaining();
    }

    ByteBuffer result = ByteBuffer.allocate(toAllocate);
    result.putInt(elementCount / 2);
    return encodeValues(encodedElements, result);
  }

  @Override
  public Value decode(@NonNull ByteBuffer bytes, @NonNull ColumnType type) {
    Collection.Builder builder = Collection.newBuilder();

    ColumnType keyType = type.parameters().get(0);
    ValueCodec keyCodec = ValueCodecs.get(keyType.rawType());

    ColumnType valueType = type.parameters().get(1);
    ValueCodec valueCodec = ValueCodecs.get(valueType.rawType());

    ByteBuffer input = bytes.duplicate();
    int elementCount = input.getInt();
    for (int i = 0; i < elementCount; i++) {
      builder.addElements(decodeValue(input, keyCodec, keyType));
      builder.addElements(decodeValue(input, valueCodec, valueType));
    }

    return Value.newBuilder().setCollection(builder).build();
  }

  private static void checkElementForNull(ByteBuffer element) {
    if (element == null) {
      throw new IllegalArgumentException("null is not supported inside maps");
    }
  }
}
