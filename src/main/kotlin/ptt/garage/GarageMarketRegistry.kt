package ptt.garage

import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.readText
import kotlin.reflect.KClass
import com.squareup.moshi.Moshi
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager
import ptt.extensions.keyOf

interface IGarageMarketRegistry {
  val items: MutableMap<String, ServerGarageItem>

  suspend fun load()
}

class GarageItemGroup(
  val itemType: GarageItemType,
  val type: KClass<out ServerGarageItem>,
  val directory: String
)

class GarageMarketRegistry : IGarageMarketRegistry, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val json by inject<Moshi>()
  private val resourceManager by inject<IResourceManager>()

  private val groups = listOf(
    GarageItemGroup(GarageItemType.Weapon, ServerGarageItemWeapon::class, "weapons/default"),
    GarageItemGroup(GarageItemType.Weapon, ServerGarageItemWeapon::class, "weapons/xt"),
    GarageItemGroup(GarageItemType.Hull, ServerGarageItemHull::class, "hulls/default"),
    GarageItemGroup(GarageItemType.Hull, ServerGarageItemHull::class, "hulls/xt"),
    GarageItemGroup(GarageItemType.Paint, ServerGarageItemPaint::class, "paints/paints"),
    GarageItemGroup(GarageItemType.Paint, ServerGarageItemPaint::class, "paints/colorings"),
    GarageItemGroup(GarageItemType.Paint, ServerGarageItemPaint::class, "paints/animations"),
    GarageItemGroup(GarageItemType.Supply, ServerGarageItemSupply::class, "supplies"),
    GarageItemGroup(GarageItemType.Subscription, ServerGarageItemSubscription::class, "subscriptions"),
    GarageItemGroup(GarageItemType.Kit, ServerGarageItemKit::class, "kits"),
    GarageItemGroup(GarageItemType.Present, ServerGarageItemPresent::class, "presents")
  )

  override val items: MutableMap<String, ServerGarageItem> = mutableMapOf()

  override suspend fun load() {
    for (group in groups) {
      logger.debug { "Загрузка группы предметов гаража ${group.itemType.name}..." }
      resourceManager.get("garage/items/${group.directory}").absolute().forEachDirectoryEntry { entry ->
        if (entry.extension != "json") return@forEachDirectoryEntry

            val item = json
              .adapter(group.type.java)
              .failOnUnknown()
              .fromJson(entry.readText())!!

            items[item.id] = item

            logger.debug { "  > Загруженный предмет гаража ${item.id} -> ${item.name.localized}" }
          }
        }

        validate()
      }

  private fun validate() {
    logger.debug { "Проверка предметов гаража..." }

    var invalid = false

    // Client requires all items to have a unique baseItemId
    items.values
      .groupBy { item -> item.baseItemId }
      .filterValues { group -> group.size > 1 }
      .let { duplicated ->
        if(duplicated.isEmpty()) return@let

        invalid = true
        duplicated.forEach { group ->
          val id = group.key
          val itemNames = group.value.joinToString(", ") { item ->
            "${item.name} (${item.id})"
          }

          logger.error { "  > Дублированный baseItemId ($id) в: $itemNames" }
        }
      }

    // Client requires all items to have a unique previewResourceId
    items.values
      .filterIsInstance<IServerGarageItemWithModifications>()
      .flatMap { item ->
        item.modifications.values.map { modification ->
          object {
            val item = item
            val modification = modification
          }
        }
      }
      .groupBy { pair -> pair.modification.previewResourceId }
      .filterValues { group -> group.size > 1 }
      .let { duplicated ->
        if(duplicated.isEmpty()) return@let

        invalid = true
        duplicated.forEach { group ->
          val id = group.key
          val itemNames = group.value.joinToString(", ") { pair ->
            val item = pair.item as ServerGarageItem
            val modificationIndex = pair.item.modifications.keyOf(pair.modification)

            "${item.name} M${modificationIndex} (${item.id}_m${modificationIndex})"
          }

          logger.error { "  > Дублированный previewResourceId ($id) в: $itemNames" }
        }
      }

    if(invalid) throw IllegalStateException("Предметы гаража недействительны")
  }
}

fun IGarageMarketRegistry.get(id: String): ServerGarageItem {
  return items[id] ?: throw Exception("Товар $id не найден")
}

inline fun <reified T : ServerGarageItem> ServerGarageItem.cast(): T {
  if(this is T) return this
  throw Exception("Несовместимый тип: ожидается ${T::class.simpleName}, получил ${this::class.simpleName}")
}
