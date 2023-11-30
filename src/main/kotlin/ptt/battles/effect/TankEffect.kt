package ptt.battles.effect

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import ptt.battles.BattleTank
import ptt.battles.sendTo
import ptt.client.TankEffectData
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.launchDelayed

abstract class TankEffect(
  val tank: BattleTank,
  val duration: Duration?,
  val cooldown: Duration?
) {
  data class EffectInfo(
    val id: Int,
    val name: String
  )
  abstract val info: EffectInfo

  var startTime: Instant? = null
    private set

  val timeLeft: Duration?
    get() = startTime?.let { Clock.System.now() - it }

  suspend fun run() {
    startTime = Clock.System.now()

    activate()
    Command(
      CommandName.EnableEffect,
      tank.id,
      info.id.toString(),
      (duration?.inWholeMilliseconds ?: 0).toString(),
      false.toString(), // Active after respawn
      0.toString() // Effect level
    ).sendTo(tank.battle)


    if(duration != null) {
      tank.coroutineScope.launchDelayed(duration) {
        deactivate()
        Command(
          CommandName.DisableEffect,
          tank.id,
          info.id.toString(),
          false.toString() // Active after respawn
        ).sendTo(tank.battle)



        tank.effects.remove(this@TankEffect)
      }
    } else {
      tank.effects.remove(this)
    }
  }

  open suspend fun activate() {}
  open suspend fun deactivate() {}
}

fun TankEffect.toTankEffectData() = TankEffectData(
  userID = tank.player.user.username,
  itemIndex = info.id,
  durationTime = timeLeft?.inWholeMilliseconds ?: 0,
  activeAfterDeath = false,
  effectLevel = 0
)
