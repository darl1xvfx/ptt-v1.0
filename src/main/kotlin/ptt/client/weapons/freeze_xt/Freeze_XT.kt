package ptt.client.weapons.freeze_xt

import com.squareup.moshi.Json
import ptt.client.Vector3Data

data class StartFire(
  @Json val physTime: Int
)

data class FireTarget(
  @Json val physTime: Int,

  @Json val targets: List<String>,
  @Json val targetIncarnations: List<Int>,

  @Json val targetPositions: List<Vector3Data>,
  @Json val hitPositions: List<Vector3Data>
)

data class StopFire(
  @Json val physTime: Int
)
