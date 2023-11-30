package ptt.battles.effect

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import ptt.battles.BattleTank
import ptt.battles.DamageType
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RepairKitEffect(
  tank: BattleTank,
  activeSoundId: Int = 20,
  deactiveSoundId: Int = 21
) : TankEffect(
  tank,
  duration = 0.7.seconds,
  cooldown = 0.3.seconds
) {
  private var totalHealing = 0.0
  private var isActive = true
  private val activeSoundId: Int = activeSoundId
  private val deactiveSoundId: Int = deactiveSoundId
  override val info: EffectInfo
    get() = EffectInfo(
      id = 1,
      name = "health"
    )

  override suspend fun activate() {
    val battle = tank.battle
    val damageProcessor = battle.damageProcessor

    totalHealing += 1000.0
    damageProcessor.heal(tank, 1000.0)

    val healingAmount = 1000
    val healingDamageType = DamageType.Heal
    Command(CommandName.DamageTank, tank.id, healingAmount.toString(), healingDamageType.key).send(tank)

    if (duration == null) return
    tank.coroutineScope.launch {
      val startTime = Clock.System.now()
      val interval = 150.milliseconds

      while (isActive && (Clock.System.now() - startTime) < duration) {
        delay(interval.inWholeMilliseconds)

        val continuousHealing = 100
        val continuousHealingDamageType = DamageType.Heal
        Command(CommandName.DamageTank, tank.id, continuousHealing.toString(), continuousHealingDamageType.key).send(
          tank
        )

        if ((Clock.System.now() - startTime) >= 0.7.seconds) {
          if (deactiveSoundId > 0) {
            Command(CommandName.SoundsOfSupplies, "", deactiveSoundId.toString()).send(tank)
          }
        }
      }
      isActive = false
    }

    if (activeSoundId > 0) {
      Command(CommandName.SoundsOfSupplies, "", activeSoundId.toString()).send(tank)
    }
  }
}
