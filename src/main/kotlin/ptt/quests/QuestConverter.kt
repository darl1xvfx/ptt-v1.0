package ptt.quests

import org.koin.core.component.KoinComponent
import ptt.client.SocketLocale

interface IQuestConverter {
  fun toClientDailyQuest(quest: ServerDailyQuest, locale: SocketLocale): DailyQuest
}

class QuestConverter : IQuestConverter, KoinComponent {
  override fun toClientDailyQuest(quest: ServerDailyQuest, locale: SocketLocale): DailyQuest {
    // ptt-(Drlxzar): Quest information
    return DailyQuest(
      canSkipForFree = false,
      description = quest.description.get(locale),
      finishCriteria = quest.required,
      image = 412322,
      questId = quest.id,
      progress = quest.current,
      skipCost = 1000,
      prizes = quest.rewards
        .sortedBy { reward -> reward.index }
        .map { reward ->
          DailyQuestPrize(
            name = reward.type.name,
            count = reward.count
          )
        }
    )
  }
}
