package ptt.battles.effect

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import ptt.battles.BattleTank
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName

class DoubleDamageEffect(
  tank: BattleTank,
  activeSoundId: Int = 24,
  deactiveSoundId: Int = 25,
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
      id = 3,
      name = "double_damage",
    )
  override suspend fun activate() {
    if (activeSoundId > 0) {
      Command(CommandName.SoundsOfSupplies, "", activeSoundId.toString()).send(tank)
    }
  }

  override suspend fun deactivate() {
    if (deactiveSoundId > 0) {
      Command(CommandName.SoundsOfSupplies, "", deactiveSoundId.toString()).send(tank)
    }
  }

  init {
    tank.coroutineScope.launch {
      delay(59000L)
      deactivate()
    }
  }
}

