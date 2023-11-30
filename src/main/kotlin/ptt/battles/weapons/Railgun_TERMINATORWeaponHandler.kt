package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.railgun_terminator.FireTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Railgun_TERMINATORWeaponHandler(
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

    var damage = random.nextInt(750, 864).coerceAtMost(864).toDouble() // так ну а тут будет такая генерация случайного урона от 750 до 864

    targetTanks.forEach { targetTank ->
      battle.damageProcessor.dealDamage(sourceTank, targetTank, damage, isCritical = false)

      // Генерация нового случайного урона для следующей итерации
      damage = random.nextInt(750, 864).coerceAtMost(864).toDouble()
    }

    Command(CommandName.ShotTarget, sourceTank.id, target.toJson()).send(battle.players.exclude(player).ready())
  }
}
