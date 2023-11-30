package ptt.battles.weapons

import kotlinx.coroutines.delay
import ptt.battles.*
import ptt.client.ChangeTankSpecificationData
import ptt.client.send
import ptt.client.weapons.freeze_xt.FireTarget
import ptt.client.weapons.freeze_xt.StartFire
import ptt.client.weapons.freeze_xt.StopFire
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class FreezeWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default
  private var fireStarted = false
  private var temperature = 0.0
  private var originalSpeed: Double? = null

  suspend fun fireStart(startFire: StartFire) {
    if (fireStarted) return

    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle
    val specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)

    fireStarted = true

    originalSpeed = specification.speed

    specification.speed = -0.3

    sendSpecificationChange(specification)

    temperature = -0.3
    Command(CommandName.Temperature, tank.id, temperature.toString()).sendTo(battle)
  }

  suspend fun fireTarget(target: FireTarget) {
    if (!fireStarted) return

    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTanks = battle.players
      .mapNotNull { player -> player.tank }
      .filter { tank -> tank.state == TankState.Active }

    targetTanks.forEach { targetTank ->
      val randomDamage = random.nextInt(90, 289).coerceAtMost(289).toDouble()

      battle.damageProcessor.dealDamage(sourceTank, targetTank, randomDamage.toDouble(), isCritical = false)
    }
  }

  suspend fun fireStop(stopFire: StopFire) {
    if (!fireStarted) return

    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle
    val specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)

    fireStarted = false

    originalSpeed?.let { specification.speed = it }
    originalSpeed = null

    sendSpecificationChange(specification)

    delay(3000)

    temperature = 0.0

    Command(CommandName.Temperature, tank.id, temperature.toString()).sendTo(battle)
  }

  private suspend fun sendSpecificationChange(specification: ChangeTankSpecificationData) {
    val tank = player.tank ?: return
    val specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toString()).send(tank)
  }
}
