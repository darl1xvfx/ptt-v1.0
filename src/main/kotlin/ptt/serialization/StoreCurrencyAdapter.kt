package ptt.serialization

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import ptt.store.StoreCurrency

class StoreCurrencyAdapter {
  @ToJson
  fun toJson(type: StoreCurrency): String = type.key

  @FromJson
  fun fromJson(value: String) = StoreCurrency.get(value)
}
