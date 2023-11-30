package ptt.battles.effect

import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds
import ptt.battles.BattleMine
import ptt.battles.BattleTank
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName

class MineEffect(
  tank: BattleTank,
  mineSoundId: Int = 28
) : TankEffect(
  tank,
  duration = 0.5.seconds,
  cooldown = null
) {
  private val mineSoundId: Int = mineSoundId

  override val info: EffectInfo
    get() = EffectInfo(
      id = 5,
      name = "mine"
    )

  override suspend fun activate() {
    val battle = tank.battle
    val mine = BattleMine(battle.mineProcessor.nextId, tank.player, tank.position)

    battle.mineProcessor.incrementId()
    battle.mineProcessor.spawn(mine)

    if ((Clock.System.now() - startTime!!) >= 0.seconds) {
      if (mineSoundId > 0) {
        Command(CommandName.SoundsOfSupplies, "", mineSoundId.toString()).send(tank)
      }
    }
  }
}
