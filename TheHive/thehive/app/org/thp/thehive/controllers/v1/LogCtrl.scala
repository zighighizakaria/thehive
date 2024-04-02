package org.thp.thehive.controllers.v1

import org.thp.scalligraph.EntityIdOrName
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperties, Query}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{IteratorOutput, Traversal}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.InputLog
import org.thp.thehive.models.{Log, Permissions, RichLog}
import org.thp.thehive.services.LogOps._
import org.thp.thehive.services.TaskOps._
import org.thp.thehive.services.{LogSrv, OrganisationSrv, TaskSrv}
import play.api.Logger
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}

@Singleton
class LogCtrl @Inject() (
    entrypoint: Entrypoint,
    db: Database,
    properties: Properties,
    logSrv: LogSrv,
    taskSrv: TaskSrv,
    organisationSrv: OrganisationSrv
) extends QueryableCtrl
    with LogRenderer {
  lazy val logger: Logger                         = Logger(getClass)
  override val entityName: String                 = "log"
  override val publicProperties: PublicProperties = properties.log
  override val initialQuery: Query =
    Query.init[Traversal.V[Log]]("listLog", (graph, authContext) => logSrv.startTraversal(graph).visible(organisationSrv)(authContext))
  override val getQuery: ParamQuery[EntityIdOrName] = Query.initWithParam[EntityIdOrName, Traversal.V[Log]](
    "getLog",
    (idOrName, graph, authContext) => logSrv.get(idOrName)(graph).visible(organisationSrv)(authContext)
  )
  override def pageQuery(limitedCountThreshold: Long): ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Log], IteratorOutput](
      "page",
      (range, logSteps, authContext) =>
        logSteps.richPage(range.from, range.to, range.extraData.contains("total"), limitedCountThreshold)(
          _.richLogWithCustomRenderer(logStatsRenderer(range.extraData - "total")(authContext))
        )
    )
  override val outputQuery: Query = Query.output[RichLog, Traversal.V[Log]](_.richLog)

  def create(taskId: String): Action[AnyContent] =
    entrypoint("create log")
      .extract("log", FieldsParser[InputLog])
      .authTransaction(db) { implicit request => implicit graph =>
        val inputLog: InputLog = request.body("log")
        for {
          task <-
            taskSrv
              .get(EntityIdOrName(taskId))
              .can(Permissions.manageTask)
              .getOrFail("Task")
          createdLog <- logSrv.create(inputLog.toLog, task, inputLog.attachment)
        } yield Results.Created(createdLog.toJson)
      }

  def update(logId: String): Action[AnyContent] =
    entrypoint("update log")
      .extract("log", FieldsParser.update("log", properties.log))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("log")
        logSrv
          .update(
            _.get(EntityIdOrName(logId))
              .can(Permissions.manageTask),
            propertyUpdaters
          )
          .map(_ => Results.NoContent)
      }

  def delete(logId: String): Action[AnyContent] =
    entrypoint("delete log")
      .authTransaction(db) { implicit req => implicit graph =>
        for {
          log <- logSrv.get(EntityIdOrName(logId)).can(Permissions.manageTask).getOrFail("Log")
          _   <- logSrv.delete(log)
        } yield Results.NoContent
      }
}
