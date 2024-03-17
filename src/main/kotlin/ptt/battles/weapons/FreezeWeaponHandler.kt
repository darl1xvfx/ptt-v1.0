package ptt.battles.weapons

import ptt.battles.*
import ptt.client.ChangeTankSpecificationData
import ptt.client.send
import ptt.client.weapons.freeze.FireTarget
import ptt.client.weapons.freeze.StartFire
import ptt.client.weapons.freeze.StopFire
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlinx.coroutines.delay

class FreezeWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
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

    specification.speed = -0.5 // Здесь предполагается, что -0.5 уменьшает скорость танка, как требуется в вашей логике.

    temperature = -0.5 // Задаем начальную температуру при начале огня.

    Command(CommandName.ClientStartFire, tank.id).send(battle.players.exclude(player).ready())
    Command(CommandName.Temperature, tank.id, temperature.toString()).sendTo(battle)
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toString()).send(tank)
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if (!fireStarted) return

    val targetTanks = battle.players
      .mapNotNull { player -> player.tank }
      .filter { tank -> target.targets.contains(tank.id) }
      .filter { tank -> tank.state == TankState.Active }

    targetTanks.forEach { targetTank ->
      val damage = damageCalculator.calculate(sourceTank, targetTank)
      if (damage.damage > 0) {
        battle.damageProcessor.dealDamage(sourceTank, targetTank, damage.damage, isCritical = damage.isCritical)

        // Здесь предполагается, что мы только устанавливаем температуру цели, если это не дружественный огонь, или если включена дружественная огонь.
        val isAlly = targetTank.player.team == player.team
        val friendlyFireEnabled = battle.properties[BattleProperty.FriendlyFireEnabled] ?: false

        if (!isAlly || friendlyFireEnabled) {
          Command(CommandName.Temperature, target.targets.contains(player.tank!!.id).toString(), temperature.toString()).sendTo(battle)

          if (temperature == -0.5) { // Проверяем, активировалось ли замораживание.
            delay(3500) // Ждем некоторое время перед проверкой температуры.
            if (temperature != -0.5) { // Если температура изменилась, значит, цель не заморожена и можно прекратить морозить.
              val param1Stop = 0.0
              Command(CommandName.Temperature, target.targets.contains(player.tank!!.id).toString(), param1Stop.toString()).sendTo(battle)
            }
          }
        }
      }
    }
  }

  suspend fun fireStop(stopFire: StopFire) {
    if (!fireStarted) return

    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle
    val specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)

    originalSpeed?.let { specification.speed = it }
    originalSpeed = null

    Command(CommandName.ChangeTankSpecification, tank.id, specification.toString()).send(tank)

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id).send(battle.players.exclude(player).ready())
  }

  private suspend fun sendSpecificationChange(specification: ChangeTankSpecificationData) {
    val tank = player.tank ?: return
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toString()).send(tank)
  }
}
