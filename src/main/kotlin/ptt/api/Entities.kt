package ptt.api

import com.squareup.moshi.Json
import ptt.client.Screen

data class PlayerStats(
  @Json val registered: Long,
  @Json val online: Int,
  @Json val screens: Map<Screen, Int>
)
