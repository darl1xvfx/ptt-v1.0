package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.weapons.freeze_xt.FireTarget
import ptt.client.weapons.freeze_xt.StartFire
import ptt.client.weapons.freeze_xt.StopFire
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Freeze_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default
  private var fireStarted = false

  suspend fun fireStart(startFire: StartFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = true

    Command(CommandName.ClientStartFire, tank.id).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    // ptt-(Drlxzar): Damage timing is not checked on server, exploitation is possible
    if (!fireStarted) return

    val targetTanks = battle.players
      .mapNotNull { player -> player.tank }
      .filter { tank -> target.targets.contains(tank.id) }
      .filter { tank -> tank.state == TankState.Active }

    targetTanks.forEach { targetTank ->
      // Генерация случайного урона от 90 до 289
      val randomDamage = random.nextInt(90, 289).coerceAtMost(289).toDouble()

      battle.damageProcessor.dealDamage(sourceTank, targetTank, randomDamage.toDouble(), isCritical = false)
    }

    // ptt-(Drlxzar): No response command?
  }

  suspend fun fireStop(stopFire: StopFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id).send(battle.players.exclude(player).ready())
  }
}
