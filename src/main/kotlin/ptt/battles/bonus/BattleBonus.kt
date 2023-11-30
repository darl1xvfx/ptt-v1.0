package ptt.battles.bonus

import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import ptt.BonusType
import ptt.battles.Battle
import ptt.battles.BattleTank
import ptt.battles.sendTo
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.launchDelayed
import ptt.math.Quaternion
import ptt.math.Vector3

abstract class BattleBonus(
  val battle: Battle,
  val id: Int,
  val position: Vector3,
  val rotation: Quaternion,
  val lifetime: Duration
) {
  abstract val type: BonusType

  var spawnTime: Instant? = null
    protected set

  val aliveFor: Duration
    get() {
      val spawnTime = spawnTime ?: throw IllegalStateException("Bonus is not spawned")
      return Clock.System.now() - spawnTime
    }

  var removeJob: Job? = null
    protected set

  val key: String
    get() = "${type.bonusKey}_$id"

  fun cancelRemove() {
    removeJob?.cancel()
    removeJob = null
  }

  open suspend fun spawn() {
    Command(
      CommandName.SpawnBonus,
      SpawnBonusDatta(
        id = key,
        x = position.x,
        y = position.y,
        z = position.z + 200.0,
        disappearing_time = lifetime.inWholeSeconds.toInt()
      ).toJson()
    ).sendTo(battle)

    spawnTime = Clock.System.now()
    removeJob = battle.coroutineScope.launchDelayed(lifetime) {
      battle.bonusProcessor.bonuses.remove(id)

      Command(CommandName.RemoveBonus, key).sendTo(battle)
    }
  }

  abstract suspend fun activate(tank: BattleTank)
}
