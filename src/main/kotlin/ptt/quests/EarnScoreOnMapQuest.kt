package ptt.quests

import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import ptt.client.SocketLocale
import ptt.client.User
import ptt.utils.LocalizedString
import ptt.utils.toLocalizedString

@Entity
@DiscriminatorValue("earn_score_map")
class EarnScoreOnMapQuest(
  id: Int,
  user: User,
  questIndex: Int,

  current: Int,
  required: Int,

  new: Boolean,
  completed: Boolean,

  rewards: MutableList<ServerDailyQuestReward>,

  val map: String
) : ServerDailyQuest(
  id, user, questIndex,
  current, required,
  new, completed,
  rewards
) {
  override val description: LocalizedString
    get() = mapOf(
      SocketLocale.English to "Earn experience on $map map",
      SocketLocale.Russian to "Набери опыт на карте $map"
    ).toLocalizedString()
}
