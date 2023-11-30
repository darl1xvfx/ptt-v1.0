package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.shaft_xt.FireTarget
import ptt.client.weapons.shaft_xt.ShotTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Shaft_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default

  suspend fun startEnergyDrain(time: Int) {
    val tank = player.tank ?: throw Exception("No Tank")

    // ptt-(Drlxzar)
  }

  suspend fun enterSnipingMode() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientEnterSnipingMode, tank.id).send(battle.players.exclude(player).ready())
  }

  suspend fun exitSnipingMode() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientExitSnipingMode, tank.id).send(battle.players.exclude(player).ready())
  }

  suspend fun fireArcade(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if(target.target != null) {
      val targetTank = battle.players
        .mapNotNull { player -> player.tank }
        .single { tank -> tank.id == target.target }
      if(targetTank.state != TankState.Active) return

      // Генерация случайного урона от 382 до 884
      val randomDamage = random.nextInt(382, 884).coerceAtMost(884).toDouble()

      battle.damageProcessor.dealDamage(sourceTank, targetTank, randomDamage.toDouble(), false)
    }

    val shot = ShotTarget(target, 5.0)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireSniping(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if(target.target != null) {
      val targetTank = battle.players
        .mapNotNull { player -> player.tank }
        .single { tank -> tank.id == target.target }
      if(targetTank.state != TankState.Active) return

      // Генерация случайного урона от 382 до 884
      val randomDamage = random.nextInt(382, 884)

      battle.damageProcessor.dealDamage(sourceTank, targetTank, randomDamage.toDouble(), false)
    }

    val shot = ShotTarget(target, 5.0)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson()).send(battle.players.exclude(player).ready())
  }
}
