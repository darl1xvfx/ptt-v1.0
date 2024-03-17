package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.railgun_xt.FireTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Railgun_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default

  suspend fun fireStart() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.StartFire, tank.id).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    // Preserve order of targets
    // ptt-(Drlxzar): Replace with a more efficient algorithm
    val targetTanks = target.targets
      .mapNotNull { username -> battle.players.singleOrNull { player -> player.user.username == username } }
      .mapNotNull { player -> player.tank }
      .filter { tank -> target.targets.contains(tank.id) }
      .filter { tank -> tank.state == TankState.Active }

    targetTanks.forEach { targetTank ->
      val damage = damageCalculator.calculate(sourceTank, targetTank)
      battle.damageProcessor.dealDamage(sourceTank, targetTank, damage.damage, isCritical = false)
    }

    Command(CommandName.ShotTarget, sourceTank.id, target.toJson()).send(battle.players.exclude(player).ready())
  }
}
