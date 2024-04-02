package org.thp.thehive.services.notification.triggers

import org.thp.thehive.models.Audit
import play.api.Configuration

import javax.inject.{Inject, Singleton}
import scala.util.{Success, Try}

@Singleton
class JobFinishedProvider @Inject() extends TriggerProvider {
  override val name: String                               = "JobFinished"
  override def apply(config: Configuration): Try[Trigger] = Success(JobFinished)
}

object JobFinished extends GlobalTrigger {
  override val name: String        = "JobFinished"
  override val auditAction: String = Audit.update
  override val entityName: String  = "Job"
}
