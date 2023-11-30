package ptt.commands.handlers

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.client.UserSocket
import ptt.client.send
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.quests.*

class QuestsHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val questConverter: IQuestConverter by inject()

  @CommandHandler(CommandName.OpenQuests)
  suspend fun openQuests(socket: UserSocket) {
    val user = socket.user ?: throw Exception("No User")
    val locale = socket.locale ?: throw IllegalStateException("Socket locale is null")

    Command(
      CommandName.ClientOpenQuests,
      OpenQuestsData(
        weeklyQuestDescription = WeeklyQuestDescriptionData(),
        quests = user.dailyQuests
          .sortedBy { quest -> quest.id }
          .map { quest -> questConverter.toClientDailyQuest(quest, locale) }
      ).toJson()
    ).send(socket)
  }

  @CommandHandler(CommandName.SkipQuestFree)
  suspend fun skipQuestFree(socket: UserSocket, questId: Int) {
    // PTT(Dr1llfix)
    Command(
      CommandName.ClientSkipQuest,
      SkipDailyQuestResponseData(
        questId = 1,
        quest = DailyQuest(
          canSkipForFree = false,
          questId = 4,
          description = "Quest 2.5",
          finishCriteria = 0,
          progress = 10,
          prizes = listOf()
        )
      ).toJson()
    ).send(socket)
  }

  @CommandHandler(CommandName.SkipQuestPaid)
  suspend fun skipQuestPaid(socket: UserSocket, questId: Int, price: Int) {
    // PTT(Dr1llfix)
    Command(
      CommandName.ClientSkipQuest,
      SkipDailyQuestResponseData(
        questId = 4,
        quest = DailyQuest(
          canSkipForFree = false,
          skipCost = 10000000,
          questId = 5,
          description = "Donate in the game",
          finishCriteria = 1000000,
          progress = 0,
          prizes = listOf(
            DailyQuestPrize(name = "Nothing", count = 0)
          )
        )
      ).toJson()
    ).send(socket)
  }

  @CommandHandler(CommandName.QuestTakePrize)
  suspend fun questTakePrize(socket: UserSocket, questId: Int) {
    Command(CommandName.ClientQuestTakePrize, questId.toString()).send(socket)
  }
}
