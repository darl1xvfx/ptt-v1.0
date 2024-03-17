package ptt.battles.effect

import kotlin.time.Duration.Companion.seconds
import ptt.battles.BattleTank
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.client.ChangeTankSpecificationData
import ptt.client.send
import ptt.client.toJson

class NitroEffect(
  tank: BattleTank,
  private val defaultSpeed: Double = 1.3,
  private val crisis10StepsSpeed: Int = 798,
  private val crisis20StepsSpeed: Int = 799,
  private var useCrisisSpeed: Boolean? = null,
  private val tankSpeed: Int = tank.hull.modification.physics.damping
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

  private var specification = ChangeTankSpecificationData.fromPhysics(
    tank.hull.modification.physics,
    tank.weapon.item.modification.physics
  )

  override suspend fun activate() {
    if (useCrisisSpeed == null) {
      useCrisisSpeed = tankSpeed == crisis10StepsSpeed || tankSpeed == crisis20StepsSpeed
    }

    if (useCrisisSpeed == true) {
      tank.effects.forEach { effect ->
        if (effect is RepairKitEffect || effect is MineEffect || effect is NitroEffect) {
          return@forEach
        }
        effect.deactivate()
        tank.effects.clear()
      }
      specification.speed += if (tankSpeed == crisis10StepsSpeed) 7 else 15
      specification.turretRotationSpeed += if (tankSpeed == crisis10StepsSpeed) 1 else 2
      specification.acceleration += if (tankSpeed == crisis10StepsSpeed) 3 else 4
    } else {
      specification.speed *= defaultSpeed
    }


    Command(CommandName.SoundsOfSupplies, "", 26.toString()).send(tank.battle.players)
    sendSpecificationChange()
  }

  override suspend fun deactivate() {
    if (useCrisisSpeed == true) {
      specification.speed -= if (tankSpeed == crisis10StepsSpeed) 7 else 15
      specification.turretRotationSpeed -= if (tankSpeed == crisis10StepsSpeed) 1 else 2
      specification.acceleration -= if (tankSpeed == crisis10StepsSpeed) 3 else 4
    } else {
      specification.speed /= defaultSpeed
    }

    Command(CommandName.SoundsOfSupplies, "", 27.toString()).send(tank.battle.players)
    sendSpecificationChange()
  }

  private suspend fun sendSpecificationChange() {
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toJson()).send(tank)
  }
}
