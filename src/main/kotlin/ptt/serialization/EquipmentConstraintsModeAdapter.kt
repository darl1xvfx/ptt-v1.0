package ptt.serialization

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import ptt.client.EquipmentConstraintsMode

class EquipmentConstraintsModeAdapter {
  @ToJson
  fun toJson(type: EquipmentConstraintsMode): String = type.key

  @FromJson
  fun fromJson(value: String) = EquipmentConstraintsMode.get(value)
}
