package ptt.battles.mode

import ptt.battles.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName

class DeathmatchModeHandler(battle: Battle) : BattleModeHandler(battle) {
  companion object {
    fun builder(): BattleModeHandlerBuilder = { battle -> DeathmatchModeHandler(battle) }
  }

  override val mode: BattleMode get() = BattleMode.Deathmatch

  override suspend fun playerJoin(player: BattlePlayer) {
    val players = battle.players.users().toStatisticsUsers()

    Command(
      CommandName.InitDmStatistics,
      InitDmStatisticsData(users = players).toJson()
    ).send(player)

    if(player.isSpectator) return

    Command(
      CommandName.BattlePlayerJoinDm,
      BattlePlayerJoinDmData(
        id = player.user.username,
        players = players
      ).toJson()
    ).send(battle.players.exclude(player).ready())
  }

  override suspend fun playerLeave(player: BattlePlayer) {
    if(player.isSpectator) return

    Command(
      CommandName.BattlePlayerLeaveDm,
      player.user.username
    ).send(battle.players.exclude(player).ready())
  }

  override suspend fun initModeModel(player: BattlePlayer) {
    Command(CommandName.InitDmModel).send(player)
  }
}
