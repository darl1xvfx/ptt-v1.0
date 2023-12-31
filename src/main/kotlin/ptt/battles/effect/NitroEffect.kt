package ptt.battles.effect

import kotlin.time.Duration.Companion.seconds
import ptt.battles.BattleTank
import ptt.client.ChangeTankSpecificationData
import ptt.client.send
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemHull

class NitroEffect(
  tank: BattleTank,
  private val defaultSpeed: Double = 1.4,
  private val crisisSpeed: Double = 2.3
) : TankEffect(
  tank,
  duration = 59.seconds,
  cooldown = 0.seconds
) {
  override val info: EffectInfo
    get() = EffectInfo(
      id = 4,
      name = "n2o"
    )

  private var specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)

  override suspend fun activate() {
    Command(CommandName.SoundsOfSupplies, "", 26.toString()).send(tank)

    specification.speed *= defaultSpeed
    sendSpecificationChange()
  }

  override suspend fun deactivate() {
    Command(CommandName.SoundsOfSupplies, "", 27.toString()).send(tank)

    specification.speed /= defaultSpeed
    sendSpecificationChange()
  }

  private suspend fun sendSpecificationChange() {
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toJson()).send(tank)
  }
}
