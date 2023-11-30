package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.smoky.Fire
import ptt.client.weapons.smoky.FireStatic
import ptt.client.weapons.smoky.FireTarget
import ptt.client.weapons.smoky.ShotTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class SmokyWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default

  suspend fun fire(fire: Fire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.Shot, tank.id, fire.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireStatic(static: FireStatic) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ShotStatic, tank.id, static.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .single { tank -> tank.id == target.target }
    if(targetTank.state != TankState.Active) return

    val damage = damageCalculator.calculate(sourceTank, targetTank)

    val isCritical = random.nextDouble() < 0.30 // Например, 30% шанс критического удара

    // Применение случайного урона и критического удара к цели
    val totalDamage = if (isCritical) damage.damage * 1.7 else damage.damage
    battle.damageProcessor.dealDamage(sourceTank, targetTank, totalDamage, isCritical)

    val shot = ShotTarget(target, damage.weakening, isCritical)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson()).send(battle.players.exclude(player).ready())
  }
}
