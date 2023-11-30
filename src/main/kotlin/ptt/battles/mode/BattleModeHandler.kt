package ptt.battles.mode

import ptt.battles.Battle
import ptt.battles.BattleMode
import ptt.battles.BattlePlayer

typealias BattleModeHandlerBuilder = (battle: Battle) -> BattleModeHandler

abstract class BattleModeHandler(
  val battle: Battle
) {
  abstract val mode: BattleMode

  abstract suspend fun playerJoin(player: BattlePlayer)
  abstract suspend fun playerLeave(player: BattlePlayer)
  abstract suspend fun initModeModel(player: BattlePlayer)
  open suspend fun initPostGui(player: BattlePlayer) {}

  open suspend fun dump(builder: StringBuilder) {}
}
