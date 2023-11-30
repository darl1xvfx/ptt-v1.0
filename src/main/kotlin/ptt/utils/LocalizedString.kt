package ptt.utils

import ptt.client.SocketLocale

class LocalizedString(
  val localized: Map<SocketLocale, String>
) {
  val default: String
    get() = localized[SocketLocale.English] ?: throw IllegalStateException("No default localized string")

  fun get(locale: SocketLocale): String = localized[locale] ?: default
}

fun Map<SocketLocale, String>.toLocalizedString() = LocalizedString(this)
