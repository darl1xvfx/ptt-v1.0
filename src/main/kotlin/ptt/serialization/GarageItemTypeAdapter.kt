package ptt.serialization

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import ptt.garage.GarageItemType

class GarageItemTypeAdapter {
  @ToJson
  fun toJson(type: GarageItemType): Int = type.key

  @FromJson
  fun fromJson(value: Int) = GarageItemType.get(value)
}
