package ptt.serialization

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import ptt.client.CaptchaLocation

class CaptchaLocationAdapter {
  @ToJson
  fun toJson(type: CaptchaLocation): String = type.key

  @FromJson
  fun fromJson(value: String) = CaptchaLocation.get(value)
}
