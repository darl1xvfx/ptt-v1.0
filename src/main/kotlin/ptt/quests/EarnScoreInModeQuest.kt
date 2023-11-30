package ptt.quests

import jakarta.persistence.Convert
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import ptt.battles.BattleMode
import ptt.client.SocketLocale
import ptt.client.User
import ptt.serialization.database.BattleModeConverter
import ptt.utils.LocalizedString
import ptt.utils.toLocalizedString

@Entity
@DiscriminatorValue("earn_score_mode")
class EarnScoreInModeQuest(
  id: Int,
  user: User,
  questIndex: Int,

  current: Int,
  required: Int,

  new: Boolean,
  completed: Boolean,

  rewards: MutableList<ServerDailyQuestReward>,

  @Convert(converter = BattleModeConverter::class)
  val mode: BattleMode
) : ServerDailyQuest(
  id, user, questIndex,
  current, required,
  new, completed,
  rewards
) {
  override val description: LocalizedString
    get() = mapOf(
      SocketLocale.English to "Earn experience in $mode",
      SocketLocale.Russian to "Набери опыт в режиме $mode"
    ).toLocalizedString()
}
