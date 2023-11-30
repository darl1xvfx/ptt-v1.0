package ptt.battles

enum class TankKillType(val key: String) {
  ByPlayer("killed"),
  SelfDestruct("suicide");

  companion object {
    private val map = BattleTeam.values().associateBy(BattleTeam::key)

    fun get(key: String) = map[key]
  }
}
