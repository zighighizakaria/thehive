package org.thp.thehive.services.notification.triggers

import org.thp.scalligraph.BadConfigurationError
import org.thp.scalligraph.models.Entity
import org.thp.scalligraph.traversal.Graph
import org.thp.thehive.models.{Audit, Organisation, User}
import play.api.{ConfigLoader, Configuration}

import scala.util.{Failure, Success, Try}

trait Trigger {
  val name: String

  def preFilter(audit: Audit with Entity, context: Option[Entity], organisation: Organisation with Entity): Boolean

  def filter(audit: Audit with Entity, context: Option[Entity], organisation: Organisation with Entity, user: Option[User with Entity])(implicit
      graph: Graph
  ): Boolean = user.fold(true)(!_.locked)

  override def toString: String = s"Trigger($name)"
}

trait TriggerProvider extends (Configuration => Try[Trigger]) {
  val name: String
  implicit class RichConfig(configuration: Configuration) {

    def getOrFail[A: ConfigLoader](path: String): Try[A] =
      configuration
        .getOptional[A](path)
        .fold[Try[A]](Failure(BadConfigurationError(s"Configuration $path is missing")))(Success(_))
  }
}
