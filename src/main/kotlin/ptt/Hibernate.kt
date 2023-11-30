package ptt

import jakarta.persistence.EntityManager
import jakarta.persistence.Persistence

object HibernateUtils {
  private val entityManagerFactory = Persistence.createEntityManagerFactory("ptt")

  fun createEntityManager(): EntityManager = entityManagerFactory.createEntityManager()
  fun close() = entityManagerFactory.close()
}
