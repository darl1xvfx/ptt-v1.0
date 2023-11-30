package ptt.battles.bonus

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import ptt.BonusType
import ptt.battles.Battle
import ptt.battles.BattleTank
import ptt.battles.sendTo
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.math.Quaternion
import ptt.math.Vector3

class BattleGoldKilledBonus(battle: Battle, id: Int, position: Vector3, rotation: Quaternion) :
  BattleBonus(battle, id, position, rotation, 10.minutes) {
  override val type: BonusType = BonusType.GoldKilled

  private val russianSpawnMessage = "Скоро будет сброшен золотой ящик"

  override suspend fun spawn() {
    Command(CommandName.SpawnGold, russianSpawnMessage, 9.toString()).sendTo(battle)
    delay(20.seconds.inWholeMilliseconds)
    super.spawn()
  }

  override suspend fun activate(tank: BattleTank) {
    tank.player.user.crystals += 1000
    tank.socket.updateCrystals()

    Command(CommandName.TakeGold, tank.id).sendTo(battle)
  }
}
