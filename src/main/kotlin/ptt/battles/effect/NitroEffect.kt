package ptt.battles.effect

import kotlin.time.Duration.Companion.seconds
import ptt.battles.BattleTank
import ptt.client.ChangeTankSpecificationData
import ptt.client.send
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandName

class NitroEffect(
  tank: BattleTank,
  activeSoundId: Int = 26,
  deactiveSoundId: Int = 27,
  val multiplier: Double = 1.4
) : TankEffect(
  tank,
  duration = 59.seconds,
  cooldown = 0.seconds
) {
  private val activeSoundId: Int = activeSoundId
  private val deactiveSoundId: Int = deactiveSoundId

  override val info: EffectInfo
    get() = EffectInfo(
      id = 4,
      name = "n2o"
    )

  var specification = ChangeTankSpecificationData.fromPhysics(tank.hull.modification.physics, tank.weapon.item.modification.physics)
    private set

  override suspend fun activate() {
    if (activeSoundId > 0) {
      Command(CommandName.SoundsOfSupplies, "", activeSoundId.toString()).send(tank)
    }

    specification.speed *= multiplier
    sendSpecificationChange()
  }

  override suspend fun deactivate() {
    if (deactiveSoundId > 0) {
      Command(CommandName.SoundsOfSupplies, "", deactiveSoundId.toString()).send(tank)
    }

    specification.speed /= multiplier
    sendSpecificationChange()
  }

  private suspend fun sendSpecificationChange() {
    Command(CommandName.ChangeTankSpecification, tank.id, specification.toJson()).send(tank)
  }
}
