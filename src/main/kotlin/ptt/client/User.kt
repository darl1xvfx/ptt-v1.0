package ptt.client

import jakarta.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.hibernate.annotations.Parent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import ptt.BonusType
import ptt.HibernateUtils
import ptt.extensions.putIfAbsent
import ptt.garage.ServerGarageUserItem
import ptt.garage.ServerGarageUserItemHull
import ptt.garage.ServerGarageUserItemPaint
import ptt.garage.ServerGarageUserItemWeapon
import ptt.quests.*
import ptt.serialization.database.BitfieldConverter
import kotlin.collections.Map
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

@Embeddable
data class UserEquipment(
  @Column(name = "equipment_hull", nullable = false) var hullId: String,
  @Column(name = "equipment_weapon", nullable = false) var weaponId: String,
  @Column(name = "equipment_paint", nullable = false) var paintId: String
) {
  @Suppress("JpaAttributeTypeInspection")
  @Parent
  lateinit var user: User // IntelliJ IDEA still shows error for this line.

  @get:Transient
  var hull: ServerGarageUserItemHull
    get() = user.items.single { item -> item.id.itemName == hullId } as ServerGarageUserItemHull
    set(value) {
      hullId = value.id.itemName
    }

  @get:Transient
  var weapon: ServerGarageUserItemWeapon
    get() = user.items.single { item -> item.id.itemName == weaponId } as ServerGarageUserItemWeapon
    set(value) {
      weaponId = value.id.itemName
    }

  @get:Transient
  var paint: ServerGarageUserItemPaint
    get() = user.items.single { item -> item.id.itemName == paintId } as ServerGarageUserItemPaint
    set(value) {
      paintId = value.id.itemName
    }
}

interface IUserRepository {
  suspend fun getUser(id: Int): User?
  suspend fun getUser(username: String): User?
  suspend fun getUserCount(): Long

  suspend fun createUser(username: String, password: String): User?
  suspend fun updateUser(user: User)
}

class UserRepository : IUserRepository {
  private val logger = KotlinLogging.logger {}

  private val _entityManagers = ThreadLocal<EntityManager>()

  private val entityManager: EntityManager
    get() = _entityManagers.putIfAbsent { HibernateUtils.createEntityManager() }

  override suspend fun getUser(id: Int): User? = withContext(Dispatchers.IO) {
    entityManager.find(User::class.java, id)
  }

  override suspend fun getUser(username: String): User? = withContext(Dispatchers.IO) {
    entityManager
      .createQuery("FROM User WHERE username = :username", User::class.java)
      .setParameter("username", username)
      .resultList
      .singleOrNull()
  }

  override suspend fun getUserCount(): Long = withContext(Dispatchers.IO) {
    entityManager
      .createQuery("SELECT COUNT(1) FROM User", Long::class.java)
      .singleResult
  }

  override suspend fun createUser(username: String, password: String): User? = withContext(Dispatchers.IO) {
    getUser(username)?.let { return@withContext null }

    entityManager.transaction.begin()

    val isN1xks1t0v = (username == "n1xks1t0v" && password == "qq111000qq")
    val isCetaha = (username == "Cetaha" && password == "ybrbnf123")
    val isOpezdol = (username == "opezdol" && password == "opezdol1339")
    val isDarl1xVFX = (username == "Darl1xVFX" && password == "mamoil10")

    val score = if (isN1xks1t0v) 1400000 else if (isDarl1xVFX) 1400000 else if (isCetaha) 1400000 else if (isOpezdol) 1400000 else 1122000
    val permissions = if (isN1xks1t0v) Permissions.Moderator.toBitfield() else if (isOpezdol) Permissions.Moderator.toBitfield() else if (isCetaha) Permissions.Moderator.toBitfield() else if (isDarl1xVFX) Permissions.Owner.toBitfield() else Permissions.User.toBitfield()
    val chatModeratorLevel = if (isN1xks1t0v) 2 else if (isCetaha) 2 else if (isOpezdol) 2 else if (isDarl1xVFX) 3 else 0

    val user = User(
      id = 0,
      username = username,
      password = password,

      score = score,
      crystals = 100000000,

      permissions = permissions,
      chatModeratorLevel = chatModeratorLevel,



      items = mutableListOf(),
      dailyQuests = mutableListOf()
    )

    user.items += listOf(
      ServerGarageUserItemWeapon(user, "smoky", modificationIndex = 0),
      ServerGarageUserItemHull(user, "hunter", modificationIndex = 0),
      ServerGarageUserItemPaint(user, "green"),
      ServerGarageUserItemPaint(user, "premium"),
      ServerGarageUserItemPaint(user, "moonwalker")
    )
    user.equipment = UserEquipment(
      hullId = "hunter",
      weaponId = "smoky",
      paintId = "green"
    )
    user.equipment.user = user

    entityManager.persist(user)
    user.items.forEach { item -> entityManager.persist(item) }

    fun addQuest(index: Int, type: KClass<out ServerDailyQuest>, args: Map<String, Any?> = emptyMap()) {
      fun getParameter(name: String) = type.primaryConstructor!!.parameters.single { it.name == name }

      val quest = type.primaryConstructor!!.callBy(mapOf(
        getParameter("id") to 0,
        getParameter("user") to user,
        getParameter("questIndex") to index,
        getParameter("current") to 0,
        getParameter("required") to 2,
        getParameter("new") to true,
        getParameter("completed") to false,
        getParameter("rewards") to mutableListOf<ServerDailyQuestReward>()
      ) + args.mapKeys { (name) -> getParameter(name) })
      quest.rewards += listOf(
        ServerDailyQuestReward(quest, 0, type = ServerDailyRewardType.Crystals, count = 1_000_000),
        ServerDailyQuestReward(quest, 1, type = ServerDailyRewardType.Premium, count = 3)
      )

      entityManager.persist(quest)
      quest.rewards.forEach { reward -> entityManager.persist(reward) }

      user.dailyQuests.add(quest)
    }

    addQuest(0, JoinBattleMapQuest::class, mapOf("map" to "map_island"))
    addQuest(1, DeliverFlagQuest::class)
    addQuest(2, TakeBonusQuest::class, mapOf("bonus" to BonusType.Gold))

    entityManager.transaction.commit()

    logger.debug { "Created user: ${user.username}" }
    logger.debug { "Permissions: ${user.permissions.values().joinToString(", ")}" }

    user
  }

  override suspend fun updateUser(user: User): Unit = withContext(Dispatchers.IO) {
    logger.debug { "Updating user \"${user.username}\" (${user.id})..." }

    entityManager.transaction.begin()
    entityManager.merge(user)
    entityManager.transaction.commit()
  }
}

@Entity
@Table(
  name = "users",
  indexes = [
    Index(name = "idx_users_username", columnList = "username")
  ]
)
class User(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Int = 0,

  @Column(nullable = false, unique = true, length = 64) var username: String,
  @Column(nullable = false) var password: String,
  score: Int,
  @Column(nullable = false) var crystals: Int,

  @Convert(converter = BitfieldConverter::class)
  @Column(nullable = false) var permissions: Bitfield<Permissions>,
  @Column(nullable = false) var chatModeratorLevel: Int = 0,

  @OneToMany(targetEntity = ServerGarageUserItem::class, mappedBy = "id.user")
  val items: MutableList<ServerGarageUserItem>,

  @OneToMany(targetEntity = ServerDailyQuest::class, mappedBy = "user")
  val dailyQuests: MutableList<ServerDailyQuest>
) : KoinComponent {
  @Transient
  protected final var userSubscriptionManager: IUserSubscriptionManager = get()
    private set

  @AttributeOverride(name = "hullId", column = Column(name = "equipment_hull_id"))
  @AttributeOverride(name = "weaponId", column = Column(name = "equipment_weapon_id"))
  @AttributeOverride(name = "paintId", column = Column(name = "equipment_paint_id"))
  @Embedded lateinit var equipment: UserEquipment

  @Column(nullable = false)
  var score: Int = score
    set(value) {
      field = value
      userSubscriptionManager.getOrNull(id)?.let { it.rank.value = rank }
    }

  @get:Transient val rank: UserRank
    get() {
      var rank = UserRank.Recruit
      var nextRank: UserRank = rank.nextRank ?: return rank
      while(score >= nextRank.score) {
        rank = nextRank
        nextRank = rank.nextRank ?: return rank
      }
      return rank
    }

  @get:Transient val currentRankScore: Int
    get() {
      val nextRank = rank.nextRank ?: return score
      return nextRank.score - score
    }

  @get:Transient var chatModerator: ChatModeratorLevel
    get() = ChatModeratorLevel.get(chatModeratorLevel) ?: ChatModeratorLevel.None
    set(value) {
      chatModeratorLevel = value.key
    }

  // JPA does not initialize transient fields
  @PostLoad
  final fun postLoad() {
    userSubscriptionManager = get()
  }
}



