package ptt.battles.weapons

import kotlinx.coroutines.delay
import ptt.battles.*
import ptt.client.send
import ptt.client.weapons.flamethrower_xt.FireTarget
import ptt.client.weapons.flamethrower_xt.StartFire
import ptt.client.weapons.flamethrower_xt.StopFire
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Flamethrower_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default
  private var fire = "0"

  suspend fun fireStart(startFire: ptt.client.weapons.flamethrower_xt.StartFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fire = "0.5"

    Command(CommandName.ClientStartFire, tank.id).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: ptt.client.weapons.flamethrower_xt.FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle
    val fires = 0.5

    if (fires > 0) {
      val targetTanks = battle.players
        .mapNotNull { player -> player.tank }
        .filter { tank -> target.targets.contains(tank.id) }
        .filter { tank -> tank.state == TankState.Active }

      targetTanks.forEach { targetTank ->
        val randomDamage = random.nextInt(97, 168).coerceAtMost(168).toDouble()
        val damage = damageCalculator.calculate(sourceTank, targetTank)
        val totalDamage = randomDamage.toDouble()
        val param1 = fire

        Command(CommandName.Temperature, targetTank.id, param1).sendTo(battle)

        battle.damageProcessor.dealDamage(sourceTank, targetTank, totalDamage, damage.isCritical)
        if (fire == "0.5") {
          delay(2000)
          if (fire != "0.5") {
            val param1Stop = "0"
            Command(CommandName.Temperature, targetTank.id, param1Stop).sendTo(battle)
          }
        }
      }
    }
  }

  suspend fun fireStop(stopFire: ptt.client.weapons.flamethrower_xt.StopFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fire = "0"

    Command(CommandName.Temperature, tank.id, fire).sendTo(battle)

    Command(CommandName.ClientStopFire, tank.id).send(battle.players.exclude(player).ready())
  }
}
