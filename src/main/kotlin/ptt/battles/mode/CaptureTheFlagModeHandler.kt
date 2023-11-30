package ptt.battles.mode

import ptt.battles.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.launchDelayed
import ptt.math.Vector3
import ptt.quests.DeliverFlagQuest
import ptt.quests.questOf
import ptt.toVector
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

abstract class FlagState(val team: BattleTeam)

class FlagOnPedestalState(team: BattleTeam) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team)"
}

class FlagDroppedState(team: BattleTeam, val position: Vector3) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team, position=$position)"
}

class FlagCarryingState(team: BattleTeam, val carrier: BattleTank) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team, carrier=${carrier.player.user.username})"
}

fun FlagState.asOnPedestal(): FlagOnPedestalState = FlagOnPedestalState(team)
fun FlagState.asDropped(position: Vector3): FlagDroppedState = FlagDroppedState(team, position)
fun FlagState.asCarrying(carrier: BattleTank): FlagCarryingState = FlagCarryingState(team, carrier)

class CaptureTheFlagModeHandler(battle: Battle) : TeamModeHandler(battle), KoinComponent {
  companion object {
    fun builder(): BattleModeHandlerBuilder = { battle -> CaptureTheFlagModeHandler(battle) }
  }

  private val userRepository by inject<IUserRepository>()
  override val mode: BattleMode get() = BattleMode.CaptureTheFlag

  val flags = mutableMapOf<BattleTeam, FlagState>(
    BattleTeam.Red to FlagOnPedestalState(BattleTeam.Red),
    BattleTeam.Blue to FlagOnPedestalState(BattleTeam.Blue)
  )

  private val flagOffsetZ = 80

  suspend fun captureFlag(flagTeam: BattleTeam, carrier: BattleTank) {
    flags[flagTeam] = flags[flagTeam]!!.asCarrying(carrier) // ptt-(Drlxzar): Non-null assertion

    Command(CommandName.FlagCaptured, carrier.id, flagTeam.key).sendTo(battle)
  }

  suspend fun dropFlag(flagTeam: BattleTeam, carrier: BattleTank, position: Vector3) {
    flags[flagTeam] = flags[flagTeam]!!.asDropped(position) // ptt-(Drlxzar): Non-null assertion
    battle.coroutineScope.launchDelayed(20.seconds) {
      val flagState = flags[flagTeam]
      if (flagState is FlagDroppedState && flagState.position == position) {
        returnFlag(flagTeam, null)
      }
    }

    Command(
      CommandName.FlagDropped,
      FlagDroppedData(
        x = position.x,
        y = position.y,
        z = position.z,
        flagTeam = flagTeam
      ).toJson()
    ).sendTo(battle)
  }

  suspend fun deliverFlag(enemyFlagTeam: BattleTeam, flagTeam: BattleTeam, carrier: BattleTank) {
    flags[enemyFlagTeam] = flags[enemyFlagTeam]!!.asOnPedestal() // ptt-(Drlxzar): Non-null assertion
    teamScores.merge(flagTeam, 1, Int::plus)

    Command(CommandName.FlagDelivered, flagTeam.key, carrier.id).sendTo(battle)
    updateScores()

    val player = carrier.player
    val fundProcessor = battle.fundProcessor

    val enemyPlayerCount = battle.players.count { it.team == enemyFlagTeam }

    if(enemyPlayerCount > 1 && !battle.properties[BattleProperty.ParkourMode]){
      val expBonus = enemyPlayerCount * 10

      val fund = when (player.user.rank.value) {
        in UserRank.Recruit.value..UserRank.Sergeant.value -> 6
        in UserRank.StaffSergeant.value..UserRank.WarrantOfficer1.value -> 9
        in UserRank.WarrantOfficer2.value..UserRank.SecondLieutenant.value -> 12
        in UserRank.Captain.value..UserRank.Generalissimo.value -> 15
        else -> 6
      }

      fundProcessor.fund += fund
      fundProcessor.updateFund()

      player.user.score += expBonus
      player.socket.updateScore()

      userRepository.updateUser(player.user)

      player.score += expBonus
      player.updateStats()

      carrier.player.user.questOf<DeliverFlagQuest>()?.let { quest ->
        quest.current++
        carrier.socket.updateQuests()
        quest.updateProgress()
      }
    }

    val scoreLimit = battle.properties[BattleProperty.ScoreLimit]
    when {
      scoreLimit != 0 && teamScores[flagTeam] == scoreLimit && battle.modeHandler is CaptureTheFlagModeHandler -> {
        battle.restart()
      }
    }
  }

  suspend fun returnFlag(flagTeam: BattleTeam, carrier: BattleTank?) {
    flags[flagTeam] = flags[flagTeam]!!.asOnPedestal() // ptt-(Drlxzar): Non-null assertion

    Command(
      CommandName.FlagReturned,
      flagTeam.key,
      carrier?.player?.user?.username ?: null.toString()
    ).sendTo(battle)
  }

  override suspend fun playerLeave(player: BattlePlayer) {
    val tank = player.tank ?: return
    val flag = flags.values.filterIsInstance<FlagCarryingState>().singleOrNull { flag -> flag.carrier == tank } ?: return
    dropFlag(flag.team, tank, tank.position)

    super.playerLeave(player)
  }

  override suspend fun initModeModel(player: BattlePlayer) {
    Command(
      CommandName.InitCtfModel,
      getCtfModel().toJson()
    ).send(player)
  }

  override suspend fun initPostGui(player: BattlePlayer) {
    Command(
      CommandName.InitFlags,
      getCtfModel().toJson()
    ).send(player)
  }

  private fun getCtfModel(): InitCtfModelData {
    val flags = battle.map.flags ?: throw IllegalStateException("Map has no flags")
    val redFlag = flags[BattleTeam.Red] ?: throw throw IllegalStateException("Map does not have a red flag")
    val blueFlag = flags[BattleTeam.Blue] ?: throw throw IllegalStateException("Map does not have a blue flag")

    val redFlagPosition = redFlag.position.toVector()
    val blueFlagPosition = blueFlag.position.toVector()

    redFlagPosition.z += flagOffsetZ
    blueFlagPosition.z += flagOffsetZ

    val redFlagState = this.flags[BattleTeam.Red] ?: throw IllegalStateException("Red flag state is null")
    val blueFlagState = this.flags[BattleTeam.Blue] ?: throw IllegalStateException("Blue flag state is null")

    return InitCtfModelData(
      resources = CtfModelResources().toJson(),
      lighting = CtfModelLighting().toJson(),
      basePosRedFlag = redFlagPosition.toVectorData(),
      basePosBlueFlag = blueFlagPosition.toVectorData(),
      posRedFlag = if(redFlagState is FlagDroppedState) redFlagState.position.toVectorData() else null,
      posBlueFlag = if(blueFlagState is FlagDroppedState) blueFlagState.position.toVectorData() else null,
      redFlagCarrierId = if(redFlagState is FlagCarryingState) redFlagState.carrier.id else null,
      blueFlagCarrierId = if(blueFlagState is FlagCarryingState) blueFlagState.carrier.id else null
    )
  }

  override suspend fun dump(builder: StringBuilder) {
    super.dump(builder)

    builder.appendLine("    Flags: ")
    flags.forEach { (team, flag) ->
      builder.appendLine("        $team: $flag")
    }
  }
}
