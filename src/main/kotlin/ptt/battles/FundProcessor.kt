package ptt.battles

import ptt.battles.mode.BattleModeHandler
import ptt.battles.mode.TeamModeHandler
import org.koin.core.component.KoinComponent
import ptt.commands.Command
import ptt.commands.CommandName
import kotlin.math.pow
import kotlin.math.roundToInt

interface IFundProcessor {
    val battle: Battle
    var fund: Int

    suspend fun updateFund()
    suspend fun calculateFund(battle: Battle, mode: BattleModeHandler): List<String>
}

class FundProcessor(
    override val battle: Battle
) : IFundProcessor, KoinComponent {
    override var fund: Int = 0

    override suspend fun updateFund() {
        Command(CommandName.ChangeFund, fund.toString()).sendTo(battle)
    }

    override suspend fun calculateFund(battle: Battle, mode: BattleModeHandler): List<String> {
        val playerPrizeList = mutableListOf<String>()
        if (mode is TeamModeHandler) {
            val redTeam = battle.players.users().filter { it.team == BattleTeam.Red }
            val blueTeam = battle.players.users().filter { it.team == BattleTeam.Blue }

            val redTeamScore = mode.teamScores[BattleTeam.Red] ?: 0
            val blueTeamScore = mode.teamScores[BattleTeam.Blue] ?: 0

            val totalTeamScores = redTeamScore + blueTeamScore

            val redPercentage = (redTeamScore.toDouble() / totalTeamScores).coerceAtLeast(0.2)
            val bluePercentage = (blueTeamScore.toDouble() / totalTeamScores).coerceAtLeast(0.2)

            val redTeamFund = redPercentage * fund
            val blueTeamFund = bluePercentage * fund

            val totalScoreRed = redTeam.sumOf { it.score }
            val totalScoreBlue = blueTeam.sumOf { it.score }

            playerPrizeList.addAll(redTeam.map { player ->
                val teamPrize = (player.score.toDouble() / totalScoreRed) * redTeamFund
                val prizeAmount = if (teamPrize.isNaN()) 0.0 else teamPrize
                "name: ${player.user.username} - prize: ${prizeAmount.coerceAtLeast(0.0).roundToInt()}"
            })

            playerPrizeList.addAll(blueTeam.map { player ->
                val teamPrize = (player.score.toDouble() / totalScoreBlue) * blueTeamFund
                val prizeAmount = if (teamPrize.isNaN()) 0.0 else teamPrize
                "name: ${player.user.username} - prize: ${prizeAmount.coerceAtLeast(0.0).roundToInt()}"
            })
        } else {
            val totalDestroyed = battle.players.sumOf { it.kills }
            val sortedPlayers = battle.players.sortedByDescending { it.kills }

            playerPrizeList.addAll(sortedPlayers.map { player ->
                val prize = fund * (player.kills.toDouble() / totalDestroyed)
                val prizeAmount = if (prize.isNaN()) 0.0 else prize
                "name: ${player.user.username} - prize: ${prizeAmount.coerceAtLeast(0.0).roundToInt()}"
            })
        }
        return playerPrizeList
    }

}
