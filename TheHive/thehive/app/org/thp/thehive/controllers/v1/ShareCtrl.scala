package org.thp.thehive.controllers.v1

import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PublicProperties, Query}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Graph, IteratorOutput, Traversal}
import org.thp.scalligraph.{AuthorizationError, BadRequestError, EntityIdOrName, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.{InputShare, ObservablesFilter, TasksFilter}
import org.thp.thehive.models._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.ShareOps._
import org.thp.thehive.services.TaskOps._
import org.thp.thehive.services._
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.Inject
import scala.util.{Failure, Success, Try}

class ShareCtrl @Inject() (
    entrypoint: Entrypoint,
    shareSrv: ShareSrv,
    properties: Properties,
    organisationSrv: OrganisationSrv,
    caseSrv: CaseSrv,
    taskSrv: TaskSrv,
    observableSrv: ObservableSrv,
    profileSrv: ProfileSrv,
    db: Database
) extends QueryableCtrl {
  override val entityName: String                 = "share"
  override val publicProperties: PublicProperties = properties.share
  override val initialQuery: Query =
    Query.init[Traversal.V[Share]]("listShare", (graph, authContext) => organisationSrv.startTraversal(graph).visible(authContext).shares)
  override def pageQuery(limitedCountThreshold: Long): ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Share], IteratorOutput](
      "page",
      (range, shareSteps, _) => shareSteps.richPage(range.from, range.to, range.extraData.contains("total"), limitedCountThreshold)(_.richShare)
    )
  override val outputQuery: Query = Query.outputWithContext[RichShare, Traversal.V[Share]]((shareSteps, _) => shareSteps.richShare)
  override val getQuery: ParamQuery[EntityIdOrName] = Query.initWithParam[EntityIdOrName, Traversal.V[Share]](
    "getShare",
    (idOrName, graph, authContext) => shareSrv.get(idOrName)(graph).visible(authContext)
  )
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[Traversal.V[Share], Traversal.V[Case]]("case", (shareSteps, _) => shareSteps.`case`),
    Query[Traversal.V[Share], Traversal.V[Observable]]("observables", (shareSteps, _) => shareSteps.observables),
    Query[Traversal.V[Share], Traversal.V[Task]]("tasks", (shareSteps, _) => shareSteps.tasks),
    Query[Traversal.V[Share], Traversal.V[Organisation]]("organisation", (shareSteps, _) => shareSteps.organisation)
  )

  def shareCase(caseId: String): Action[AnyContent] =
    entrypoint("create case shares")
      .extract("shares", FieldsParser[InputShare].sequence.on("shares"))
      .authTransaction(db) { implicit request => implicit graph =>
        val inputShares: Seq[InputShare] = request.body("shares")
        caseSrv
          .get(EntityIdOrName(caseId))
          .can(Permissions.manageShare)
          .getOrFail("Case")
          .flatMap { `case` =>
            inputShares.toTry { inputShare =>
              for {
                organisation <-
                  organisationSrv
                    .get(request.organisation)
                    .visibleOrganisationsFrom
                    .get(EntityIdOrName(inputShare.organisationName))
                    .getOrFail("Organisation")
                profile   <- profileSrv.getOrFail(EntityIdOrName(inputShare.profile))
                share     <- shareSrv.shareCase(owner = false, `case`, organisation, profile)
                richShare <- shareSrv.get(share).richShare.getOrFail("Share")
                _         <- if (inputShare.tasks == TasksFilter.all) shareSrv.shareCaseTasks(share) else Success(Nil)
                _         <- if (inputShare.observables == ObservablesFilter.all) shareSrv.shareCaseObservables(share) else Success(Nil)
              } yield richShare
            }
          }
          .map(shares => Results.Ok(shares.toJson))
      }

  def removeShare(shareId: String): Action[AnyContent] =
    entrypoint("remove share")
      .authTransaction(db) { implicit request => implicit graph =>
        doRemoveShare(EntityIdOrName(shareId)).map(_ => Results.NoContent)
      }

  def removeShares(): Action[AnyContent] =
    entrypoint("remove share")
      .extract("shares", FieldsParser[String].sequence.on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val shareIds: Seq[String] = request.body("shares")
        shareIds.map(EntityIdOrName.apply).toTry(doRemoveShare(_)).map(_ => Results.NoContent)
      }

  def removeShares(caseId: String): Action[AnyContent] =
    entrypoint("remove share")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")
        organisations
          .map(EntityIdOrName(_))
          .toTry { organisationId =>
            for {
              organisation <- organisationSrv.get(organisationId).getOrFail("Organisation")
              _ <-
                if (request.organisation.fold(_ == organisation._id, _ == organisation.name))
                  Failure(BadRequestError("You cannot remove your own share"))
                else Success(())
              shareId <-
                caseSrv
                  .get(EntityIdOrName(caseId))
                  .can(Permissions.manageShare)
                  .share(organisationId)
                  .has(_.owner, false)
                  .orFail(AuthorizationError("Operation not permitted"))
              _ <- shareSrv.delete(shareId)
            } yield ()
          }
          .map(_ => Results.NoContent)
      }

  def removeTaskShares(taskId: String): Action[AnyContent] =
    entrypoint("remove share tasks")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")

        taskSrv
          .getOrFail(EntityIdOrName(taskId))
          .flatMap { task =>
            organisations.toTry { organisationName =>
              organisationSrv
                .getOrFail(EntityIdOrName(organisationName))
                .flatMap(shareSrv.unshareTask(task, _))
            }
          }
          .map(_ => Results.NoContent)
      }

  def removeObservableShares(observableId: String): Action[AnyContent] =
    entrypoint("remove share observables")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")

        observableSrv
          .getOrFail(EntityIdOrName(observableId))
          .flatMap { observable =>
            organisations.toTry { organisationName =>
              organisationSrv
                .getOrFail(EntityIdOrName(organisationName))
                .flatMap(shareSrv.unshareObservable(observable, _))
            }
          }
          .map(_ => Results.NoContent)
      }

  private def doRemoveShare(shareId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    if (!shareSrv.get(shareId).`case`.can(Permissions.manageShare).exists)
      Failure(AuthorizationError("You are not authorized to remove share"))
    else if (shareSrv.get(shareId).byOrganisation(authContext.organisation).exists)
      Failure(AuthorizationError("You can't remove your share"))
    else if (shareSrv.get(shareId).has(_.owner, true).exists)
      Failure(AuthorizationError("You can't remove initial shares"))
    else
      shareSrv.unshareCase(shareId)

  def updateShare(shareId: String): Action[AnyContent] =
    entrypoint("update share")
      .extract("profile", FieldsParser.string.on("profile"))
      .authTransaction(db) { implicit request => implicit graph =>
        val profile: String = request.body("profile")
        if (!shareSrv.get(EntityIdOrName(shareId)).`case`.can(Permissions.manageShare).exists)
          Failure(AuthorizationError("You are not authorized to remove share"))
        for {
          richShare <-
            shareSrv
              .get(EntityIdOrName(shareId))
              .filter(_.organisation.visibleOrganisationsTo.visible)
              .richShare
              .getOrFail("Share")
          profile <- profileSrv.getOrFail(EntityIdOrName(profile))
          _       <- shareSrv.updateProfile(richShare.share, profile)
        } yield Results.Ok
      }

  def listShareCases(caseId: String): Action[AnyContent] =
    entrypoint("list case shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = caseSrv
          .get(EntityIdOrName(caseId))
          .shares
          .visible
          .filterNot(_.get(request.organisation))
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def listShareTasks(caseId: String, taskId: String): Action[AnyContent] =
    entrypoint("list task shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = caseSrv
          .get(EntityIdOrName(caseId))
          .can(Permissions.manageShare)
          .shares
          .visible
          .filterNot(_.get(request.organisation))
          .byTask(EntityIdOrName(taskId))
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def listShareObservables(caseId: String, observableId: String): Action[AnyContent] =
    entrypoint("list observable shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = caseSrv
          .get(EntityIdOrName(caseId))
          .can(Permissions.manageShare)
          .shares
          .visible
          .filterNot(_.get(request.organisation))
          .byObservable(EntityIdOrName(observableId))
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def shareTask(taskId: String): Action[AnyContent] =
    entrypoint("share task")
      .extract("organisations", FieldsParser.string.sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisationIds: Seq[String] = request.body("organisations")

        for {
          task          <- taskSrv.getOrFail(EntityIdOrName(taskId))
          _             <- taskSrv.get(task).`case`.can(Permissions.manageShare).existsOrFail
          organisations <- organisationIds.map(EntityIdOrName(_)).toTry(organisationSrv.get(_).visible.getOrFail("Organisation"))
          _             <- shareSrv.addTaskShares(task, organisations)
        } yield Results.NoContent
      }

  def shareObservable(observableId: String): Action[AnyContent] =
    entrypoint("share observable")
      .extract("organisations", FieldsParser.string.sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisationIds: Seq[String] = request.body("organisations")
        for {
          observable    <- observableSrv.getOrFail(EntityIdOrName(observableId))
          _             <- observableSrv.get(observable).`case`.can(Permissions.manageShare).existsOrFail
          organisations <- organisationIds.map(EntityIdOrName(_)).toTry(organisationSrv.get(_).visible.getOrFail("Organisation"))
          _             <- shareSrv.addObservableShares(observable, organisations)
        } yield Results.NoContent
      }
}
