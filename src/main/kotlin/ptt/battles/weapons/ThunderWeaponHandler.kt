package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.toVector
import ptt.client.weapons.thunder.Fire
import ptt.client.weapons.thunder.FireStatic
import ptt.client.weapons.thunder.FireTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import ptt.math.Vector3
import ptt.math.Vector3Constants
import ptt.math.distanceTo
import kotlin.random.Random

class ThunderWeaponHandler(
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

    processSplashTargets(static.hitPoint.toVector(), static.splashTargetIds, static.splashTargetDistances)

    Command(CommandName.ShotStatic, tank.id, static.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .singleOrNull { tank -> tank.id == target.target }

    targetTank?.let {
      if (it.state != TankState.Active) return@let

      // Так ну тут генерация случайного урона от 276 до 693
      val randomDamage = random.nextInt(276, 693).coerceAtMost(693).toDouble()

      battle.damageProcessor.dealDamage(sourceTank, it, randomDamage, false)

      processSplashTargets(target.hitPointWorld.toVector(), target.splashTargetIds, target.splashTargetDistances)

      Command(CommandName.ShotTarget, sourceTank.id, target.toJson()).send(battle.players.exclude(player).ready())
    }
  }

  private suspend fun processSplashTargets(hitPoint: Vector3, ids: List<String>, distances: List<String>) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    ids.forEach { id ->
      val targetTank = battle.players
        .mapNotNull { player -> player.tank }
        .singleOrNull { tank -> tank.id == id }

      targetTank?.let {
        if (it.state != TankState.Active) return@let

        val distance = hitPoint.distanceTo(it.position) * Vector3Constants.TO_METERS
        val damage = damageCalculator.calculate(sourceTank.weapon, distance, splash = true)
        if (damage.damage < 0) return@forEach

        battle.damageProcessor.dealDamage(sourceTank, it, damage.damage, damage.isCritical)
      }
    }
  }
}