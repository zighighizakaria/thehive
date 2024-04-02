package org.thp.thehive.controllers.v0

import org.apache.tinkerpop.gremlin.process.traversal.P
import org.thp.scalligraph._
import org.thp.scalligraph.controllers.{Entrypoint, FPathElem, FPathEmpty, FieldsParser}
import org.thp.scalligraph.models.{Database, UMapping}
import org.thp.scalligraph.query.PredicateOps._
import org.thp.scalligraph.query._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, IteratorOutput, Traversal}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.{InputCase, InputTask}
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.CustomFieldOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.ProcedureOps._
import org.thp.thehive.services.UserOps._
import org.thp.thehive.services._
import play.api.libs.json._
import play.api.mvc.{Action, AnyContent, Results}

import java.util.{Map => JMap}
import javax.inject.{Inject, Named, Singleton}
import scala.util.{Failure, Success}

@Singleton
class CaseCtrl @Inject() (
    override val entrypoint: Entrypoint,
    caseSrv: CaseSrv,
    caseTemplateSrv: CaseTemplateSrv,
    userSrv: UserSrv,
    organisationSrv: OrganisationSrv,
    override val publicData: PublicCase,
    @Named("v0") override val queryExecutor: QueryExecutor,
    implicit override val db: Database
) extends CaseRenderer
    with QueryCtrl {
  def create: Action[AnyContent] =
    entrypoint("create case")
      .extract("case", FieldsParser[InputCase])
      .extract("tasks", FieldsParser[InputTask].sequence.on("tasks"))
      .extract("caseTemplate", FieldsParser[String].optional.on("template"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String] = request.body("caseTemplate")
        val inputCase: InputCase             = request.body("case")
        val inputTasks: Seq[InputTask]       = request.body("tasks")
        val customFields                     = inputCase.customFields.map(c => InputCustomFieldValue(c.name, c.value, c.order))
        for {
          organisation <-
            userSrv
              .current
              .organisations(Permissions.manageCase)
              .get(request.organisation)
              .orFail(AuthorizationError("Operation not permitted"))
          user         <- inputCase.user.fold(userSrv.current.getOrFail("User"))(userSrv.getByName(_).getOrFail("User"))
          caseTemplate <- caseTemplateName.map(ct => caseTemplateSrv.get(EntityIdOrName(ct)).visible.richCaseTemplate.getOrFail("CaseTemplate")).flip
          richCase <- caseSrv.create(
            caseTemplate.fold(inputCase)(inputCase.withCaseTemplate).toCase,
            Some(user),
            organisation,
            customFields,
            caseTemplate,
            inputTasks.map(_.toTask)
          )
        } yield Results.Created(richCase.toJson)
      }

  def get(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("get case")
      .extract("stats", FieldsParser.boolean.optional.on("nstats"))
      .authRoTransaction(db) { implicit request => implicit graph =>
        val c = caseSrv
          .get(EntityIdOrName(caseIdOrNumber))
          .visible(organisationSrv)
        val stats: Option[Boolean] = request.body("stats")
        if (stats.contains(true))
          c.richCaseWithCustomRenderer(caseStatsRenderer(request))
            .getOrFail("Case")
            .map {
              case (richCase, stats) => Results.Ok(richCase.toJson.as[JsObject] + ("stats" -> stats))
            }
        else
          c.richCase
            .getOrFail("Case")
            .map(richCase => Results.Ok(richCase.toJson))
      }

  def update(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("update case")
      .extract("case", FieldsParser.update("case", publicData.publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("case")
        caseSrv
          .update(
            _.get(EntityIdOrName(caseIdOrNumber))
              .can(Permissions.manageCase),
            propertyUpdaters
          )
          .flatMap {
            case (caseSteps, _) =>
              caseSteps
                .richCase
                .getOrFail("Case")
                .map(richCase => Results.Ok(richCase.toJson))
          }
      }

  def bulkUpdate: Action[AnyContent] =
    entrypoint("update case")
      .extract("case", FieldsParser.update("case", publicData.publicProperties))
      .extract("idsOrNumbers", FieldsParser.seq[String].on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("case")
        val idsOrNumbers: Seq[String]              = request.body("idsOrNumbers")
        idsOrNumbers
          .toTry { caseIdOrNumber =>
            caseSrv
              .update(
                _.get(EntityIdOrName(caseIdOrNumber)).can(Permissions.manageCase),
                propertyUpdaters
              )
              .flatMap {
                case (caseSteps, _) =>
                  caseSteps
                    .richCase
                    .getOrFail("Case")
              }
          }
          .map(richCases => Results.Ok(richCases.toJson))
      }

  def delete(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("delete case")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          c <-
            caseSrv
              .get(EntityIdOrName(caseIdOrNumber))
              .can(Permissions.manageCase)
              .getOrFail("Case")
          _ <- caseSrv.delete(c)
        } yield Results.NoContent
      }

  def merge(caseId: String, caseToMerge: String): Action[AnyContent] =
    entrypoint("merge cases")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          caze    <- caseSrv.get(EntityIdOrName(caseId)).visible(organisationSrv).getOrFail("Case")
          toMerge <- caseSrv.get(EntityIdOrName(caseToMerge)).visible(organisationSrv).getOrFail("Case")
          merged  <- caseSrv.merge(Seq(caze, toMerge))
        } yield Results.Created(merged.toJson)
      }

  def linkedCases(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("case link")
      .auth { implicit request =>
        val src = db.source { implicit graph =>
          caseSrv
            .get(EntityIdOrName(caseIdOrNumber))
            .visible(organisationSrv)
            .linkedCases
            .domainMap {
              case (c, o) =>
                c.toJson.as[JsObject] +
                  ("linkedWith" -> o.toJson) +
                  ("linksCount" -> JsNumber(o.size))
            }
            .toIterator
        }
        Success(Results.Ok.chunked(src.map(_.toString).intersperse("[", ",", "]"), Some("application/json")))
      }
}

@Singleton
class PublicCase @Inject() (
    caseSrv: CaseSrv,
    organisationSrv: OrganisationSrv,
    observableSrv: ObservableSrv,
    userSrv: UserSrv,
    customFieldSrv: CustomFieldSrv,
    implicit val db: Database
) extends PublicData
    with CaseRenderer {
  override val entityName: String = "case"
  override val initialQuery: Query =
    Query.init[Traversal.V[Case]]("listCase", (graph, authContext) => caseSrv.startTraversal(graph).visible(organisationSrv)(authContext))
  override val getQuery: ParamQuery[EntityIdOrName] =
    Query.initWithParam[EntityIdOrName, Traversal.V[Case]](
      "getCase",
      (idOrName, graph, authContext) => caseSrv.get(idOrName)(graph).visible(organisationSrv)(authContext)
    )
  override def pageQuery(limitedCountThreshold: Long): ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Case], IteratorOutput](
      "page",
      {
        case (OutputParam(from, to, withStats, _), caseSteps, authContext) =>
          caseSteps
            .richPage(from, to, withTotal = true, limitedCountThreshold) {
              case c if withStats =>
                c.richCaseWithCustomRenderer(caseStatsRenderer(authContext))(authContext)
              case c =>
                c.richCase(authContext).domainMap(_ -> JsObject.empty)
            }
      }
    )
  override val outputQuery: Query = Query.outputWithContext[RichCase, Traversal.V[Case]]((caseSteps, authContext) => caseSteps.richCase(authContext))
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[Traversal.V[Case], Traversal.V[Observable]](
      "observables",
      (caseSteps, authContext) =>
        // caseSteps.observables(authContext)
        observableSrv.startTraversal(caseSteps.graph).has(_.relatedId, P.within(caseSteps._id.toSeq: _*)).visible(organisationSrv)(authContext)
    ),
    Query[Traversal.V[Case], Traversal.V[Task]](
      "tasks",
      (caseSteps, authContext) => caseSteps.tasks(authContext)
//        taskSrv.startTraversal(caseSteps.graph).has(_.relatedId, P.within(caseSteps._id.toSeq: _*)).visible(organisationSrv)(authContext)
    ),
    Query[Traversal.V[Case], Traversal.V[User]]("assignableUsers", (caseSteps, authContext) => caseSteps.assignableUsers(authContext)),
    Query[Traversal.V[Case], Traversal.V[Organisation]]("organisations", (caseSteps, authContext) => caseSteps.organisations.visible(authContext)),
    Query[Traversal.V[Case], Traversal.V[Alert]]("alerts", (caseSteps, authContext) => caseSteps.alert.visible(organisationSrv)(authContext)),
    Query[Traversal.V[Case], Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]]](
      "linkedCases",
      (caseSteps, authContext) =>
        caseSteps
          .linkedCases(authContext)
          .domainMap {
            case (c, o) =>
              c.toJson.as[JsObject] +
                ("linkedWith" -> o.toJson) +
                ("linksCount" -> JsNumber(o.size))
          }
    )
  )
  override val publicProperties: PublicProperties =
    PublicPropertyListBuilder[Case]
      .property("caseId", UMapping.int)(_.rename("number").readonly)
      .property("title", UMapping.string)(_.field.updatable)
      .property("description", UMapping.string)(_.field.updatable)
      .property("severity", UMapping.int)(_.field.updatable)
      .property("startDate", UMapping.date)(_.field.updatable)
      .property("endDate", UMapping.date.optional)(_.field.updatable)
      .property("tags", UMapping.string.set)(
        _.field
          .custom { (_, value, vertex, graph, authContext) =>
            caseSrv
              .get(vertex)(graph)
              .getOrFail("Case")
              .flatMap(`case` => caseSrv.updateTags(`case`, value)(graph, authContext))
              .map(_ => Json.obj("tags" -> value))
          }
      )
      .property("flag", UMapping.boolean)(_.field.updatable)
      .property("tlp", UMapping.int)(_.field.updatable)
      .property("pap", UMapping.int)(_.field.updatable)
      .property("status", UMapping.enum[CaseStatus.type])(_.field.updatable)
      .property("summary", UMapping.string.optional)(_.field.updatable)
      .property("owner", UMapping.string.optional)(_.rename("assignee").custom { (_, login, vertex, graph, authContext) =>
        for {
          c    <- caseSrv.get(vertex)(graph).getOrFail("Case")
          user <- login.map(u => userSrv.get(EntityIdOrName(u))(graph).getOrFail("User")).flip
          _ <- user match {
            case Some(u) => caseSrv.assign(c, u)(graph, authContext)
            case None    => caseSrv.unassign(c)(graph, authContext)
          }
        } yield Json.obj("owner" -> user.map(_.login))
      })
      .property("resolutionStatus", UMapping.string.optional)(_.field.custom { (_, resolutionStatus, vertex, graph, authContext) =>
        for {
          c <- caseSrv.get(vertex)(graph).getOrFail("Case")
          _ <- resolutionStatus match {
            case Some(s) => caseSrv.setResolutionStatus(c, s)(graph, authContext)
            case None    => caseSrv.unsetResolutionStatus(c)(graph, authContext)
          }
        } yield Json.obj("resolutionStatus" -> resolutionStatus)
      })
      .property("impactStatus", UMapping.string.optional)(_.field.custom { (_, impactStatus, vertex, graph, authContext) =>
        for {
          c <- caseSrv.get(vertex)(graph).getOrFail("Case")
          _ <- impactStatus match {
            case Some(s) => caseSrv.setImpactStatus(c, s)(graph, authContext)
            case None    => caseSrv.unsetImpactStatus(c)(graph, authContext)
          }
        } yield Json.obj("impactStatus" -> impactStatus)
      })
      .property("customFields", UMapping.jsonNative)(_.subSelect {
        case (FPathElem(_, FPathElem(idOrName, _)), caseSteps) =>
          caseSteps.customFieldJsonValue(customFieldSrv, EntityIdOrName(idOrName))
        case (_, caseSteps) => caseSteps.customFields.nameJsonValue.fold.domainMap(JsObject(_))
      }
        .filter[JsValue] {
          case (FPathElem(_, FPathElem(name, _)), caseTraversal, _, predicate) =>
            predicate match {
              case Right(predicate) => caseTraversal.customFieldFilter(customFieldSrv, EntityIdOrName(name), predicate)
              case Left(true)       => caseTraversal.hasCustomField(customFieldSrv, EntityIdOrName(name))
              case Left(false)      => caseTraversal.hasNotCustomField(customFieldSrv, EntityIdOrName(name))
            }
          case (_, caseTraversal, _, _) => caseTraversal.empty
        }
        .custom {
          case (FPathElem(_, FPathElem(name, _)), value, vertex, graph, authContext) =>
            for {
              c <- caseSrv.get(vertex)(graph).getOrFail("Case")
              _ <- caseSrv.setOrCreateCustomField(c, EntityIdOrName(name), Some(value), None)(graph, authContext)
            } yield Json.obj(s"customField.$name" -> value)
          case (FPathElem(_, FPathEmpty), values: JsObject, vertex, graph, authContext) =>
            for {
              c   <- caseSrv.get(vertex)(graph).getOrFail("Case")
              cfv <- values.fields.toTry { case (n, v) => customFieldSrv.getOrFail(EntityIdOrName(n))(graph).map(cf => (cf, v, None)) }
              _   <- caseSrv.updateCustomField(c, cfv)(graph, authContext)
            } yield Json.obj("customFields" -> values)
          case _ => Failure(BadRequestError("Invalid custom fields format"))
        })
      .property("computed.handlingDuration", UMapping.long)(_.select(_.handlingDuration).readonly)
      .property("computed.handlingDurationInSeconds", UMapping.long)(_.select(_.handlingDuration.math("_ / 1000").domainMap(_.toLong)).readonly)
      .property("computed.handlingDurationInMinutes", UMapping.long)(_.select(_.handlingDuration.math("_ / 60000").domainMap(_.toLong)).readonly)
      .property("computed.handlingDurationInHours", UMapping.long)(_.select(_.handlingDuration.math("_ / 3600000").domainMap(_.toLong)).readonly)
      .property("computed.handlingDurationInDays", UMapping.long)(_.select(_.handlingDuration.math("_ / 86400000").domainMap(_.toLong)).readonly)
      .property("viewingOrganisation", UMapping.string)(
        _.select(t => t.value(_.organisationIds).domainMap(organisationSrv.getName(_)(t.graph)))
          .filter[String] {
            case (_, caseTraversal, _, Right(orgNamePredicate)) =>
              val organisationId = orgNamePredicate.mapValue(o => organisationSrv.getId(EntityIdOrName(o))(caseTraversal.graph))
              caseTraversal.has(_.organisationIds, organisationId)
            case (_, caseTraversal, _, Left(true)) =>
              caseTraversal
            case (_, caseTraversal, _, Left(false)) =>
              caseTraversal.empty
          }
          .readonly
      )
      .property("owningOrganisation", UMapping.string)(
        _.select(t => t.value(_.owningOrganisation).domainMap(organisationSrv.getName(_)(t.graph)))
          .filter[String] {
            case (_, caseTraversal, _, Right(orgNamePredicate)) =>
              val organisationId = orgNamePredicate.mapValue(o => organisationSrv.getId(EntityIdOrName(o))(caseTraversal.graph))
              caseTraversal.has(_.owningOrganisation, organisationId)
            case (_, caseTraversal, _, Left(true)) =>
              caseTraversal
            case (_, caseTraversal, _, Left(false)) =>
              caseTraversal.empty
          }
          .readonly
      )
      .property("patternId", UMapping.string.sequence)(_.select(_.procedure.pattern.value(_.patternId)).readonly)
      .build
}
