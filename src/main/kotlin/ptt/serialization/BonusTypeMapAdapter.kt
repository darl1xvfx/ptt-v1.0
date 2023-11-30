package ptt.serialization

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import ptt.BonusType

class BonusTypeMapAdapter {
  @ToJson
  fun toJson(type: BonusType): String = type.mapKey

  @FromJson
  fun fromJson(value: String) = BonusType.get(value)
}
