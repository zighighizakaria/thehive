package org.thp.thehive.services

import akka.actor.ActorRef
import com.google.inject.name.Named
import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.apache.tinkerpop.gremlin.structure.Transaction.Status
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.thp.scalligraph.EntityId
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models._
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, Graph, IdentityConverter, Traversal}
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.AuditOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.DashboardOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.ShareOps._
import org.thp.thehive.services.TaskOps._
import org.thp.thehive.services.notification.AuditNotificationMessage
import play.api.libs.json.{JsObject, JsValue, Json}

import java.util.{Map => JMap}
import javax.inject.{Inject, Provider, Singleton}
import scala.util.{Success, Try}

case class PendingAudit(audit: Audit, context: Product with Entity, `object`: Option[Product with Entity])

@Singleton
class AuditSrv @Inject() (
    userSrvProvider: Provider[UserSrv],
    @Named("notification-actor") notificationActor: ActorRef,
    eventSrv: EventSrv,
    db: Database
) extends VertexSrv[Audit] { auditSrv =>
  lazy val userSrv: UserSrv                                = userSrvProvider.get
  val auditUserSrv                                         = new EdgeSrv[AuditUser, Audit, User]
  val auditedSrv                                           = new EdgeSrv[Audited, Audit, Product]
  val auditContextSrv                                      = new EdgeSrv[AuditContext, Audit, Product]
  val `case`                                               = new SelfContextObjectAudit[Case]
  val task                                                 = new SelfContextObjectAudit[Task]
  val observable                                           = new SelfContextObjectAudit[Observable]
  val log                                                  = new ObjectAudit[Log, Task]
  val caseTemplate                                         = new SelfContextObjectAudit[CaseTemplate]
  val taskInTemplate                                       = new ObjectAudit[Task, CaseTemplate]
  val alert                                                = new AlertAudit
  val share                                                = new ShareAudit
  val observableInAlert                                    = new ObjectAudit[Observable, Alert]
  val user                                                 = new UserAudit
  val dashboard                                            = new SelfContextObjectAudit[Dashboard]
  val organisation                                         = new SelfContextObjectAudit[Organisation]
  val profile                                              = new SelfContextObjectAudit[Profile]
  val pattern                                              = new SelfContextObjectAudit[Pattern]
  val procedure                                            = new ObjectAudit[Procedure, Case]
  val customField                                          = new SelfContextObjectAudit[CustomField]
  val page                                                 = new SelfContextObjectAudit[Page]
  private val pendingAuditsLock                            = new Object
  private val transactionAuditIdsLock                      = new Object
  private val unauditedTransactionsLock                    = new Object
  private var pendingAudits: Map[Graph, PendingAudit]      = Map.empty
  private var transactionAuditIds: List[(Graph, EntityId)] = Nil
  private var unauditedTransactions: Set[Graph]            = Set.empty

  /**
    * Gets the main action Audits by ids sorted by date
    * @param order the sort
    * @param ids the ids
    * @param graph db
    * @return
    */
  def getMainByIds(order: Order, ids: EntityId*)(implicit graph: Graph): Traversal.V[Audit] =
    getByIds(ids: _*)
      .has(_.mainAction, true)
      .sort(_.by("_createdAt", order))

  def mergeAudits[R](body: => Try[R])(auditCreator: R => Try[Unit])(implicit graph: Graph): Try[R] = {
    unauditedTransactionsLock.synchronized {
      unauditedTransactions = unauditedTransactions + graph
    }
    val result = body
    unauditedTransactionsLock.synchronized {
      unauditedTransactions = unauditedTransactions - graph
    }
    result.flatMap { r =>
      auditCreator(r).map(_ => r)
    }
  }

  def flushPendingAudit()(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    logger.debug("Store last audit")
    pendingAudits.get(graph).fold[Try[Unit]](Success(())) { p =>
      pendingAuditsLock.synchronized {
        pendingAudits = pendingAudits - graph
      }
      createFromPending(p.audit.copy(mainAction = true), p.context, p.`object`).map { _ =>
        val (ids, otherTxIds) = transactionAuditIds.partition(_._1 == graph)
        transactionAuditIdsLock.synchronized {
          transactionAuditIds = otherTxIds
        }
        db.addTransactionListener {
          case Status.COMMIT =>
            logger.debug("Sending audit to stream bus and to notification actor")
            val auditIds = ids.map(_._2)
            eventSrv.publish(StreamTopic.dispatcher)(AuditStreamMessage(auditIds: _*))
            notificationActor ! AuditNotificationMessage(auditIds: _*)
          case _ =>
        }
      }
    }
  }

  private def createFromPending(audit: Audit, context: Product with Entity, `object`: Option[Product with Entity])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    logger.debug(s"Store audit entity: $audit")
    for {
      user         <- userSrv.current.getOrFail("User")
      createdAudit <- createEntity(audit)
      _            <- auditUserSrv.create(AuditUser(), createdAudit, user)
      _            <- `object`.map(auditedSrv.create(Audited(), createdAudit, _)).flip
      _ = auditContextSrv.create(AuditContext(), createdAudit, context) // this could fail on delete (context doesn't exist)
    } yield transactionAuditIdsLock.synchronized {
      transactionAuditIds = (graph -> createdAudit._id) :: transactionAuditIds
    }
  }

  def create(audit: Audit, context: Product with Entity, `object`: Option[Product with Entity])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    def setupCallbacks(): Try[Unit] = {
      logger.debug("Setup callbacks for the current transaction")
      db.addTransactionListener {
        case Status.ROLLBACK =>
          pendingAuditsLock.synchronized {
            pendingAudits = pendingAudits - graph
          }
          transactionAuditIdsLock.synchronized {
            transactionAuditIds = transactionAuditIds.filterNot(_._1 == graph)
          }
        case _ =>
      }
      db.addCallback(() => flushPendingAudit())
      Success(())
    }

    if (unauditedTransactions.contains(graph)) {
      logger.debug(s"Audit is disable to the current transaction, $audit ignored.")
      Success(())
    } else {
      logger.debug(s"Hold $audit, store previous audit if any")
      val p = pendingAudits.get(graph)
      pendingAuditsLock.synchronized {
        pendingAudits = pendingAudits + (graph -> PendingAudit(audit, context, `object`))
      }
      p.fold(setupCallbacks())(p => createFromPending(p.audit, p.context, p.`object`))
    }
  }

  def getObject(audit: Audit with Entity)(implicit graph: Graph): Option[Product with Entity] = get(audit).`object`.entity.headOption

  class ObjectAudit[E <: Product, C <: Product] {

    def create(entity: E with Entity, context: C with Entity, details: JsValue)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      auditSrv.create(Audit(Audit.create, entity, Some(details.toString)), context, Some(entity))

    def update(entity: E with Entity, context: C with Entity, details: JsObject)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      if (details == JsObject.empty) Success(())
      else auditSrv.create(Audit(Audit.update, entity, Some(details.toString)), context, Some(entity))

    def delete(entity: E with Entity, context: C with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      auditSrv.create(Audit(Audit.delete, entity, None), context, None)

  }

  class SelfContextObjectAudit[E <: Product] {

    def create(entity: E with Entity, details: JsValue)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      auditSrv.create(Audit(Audit.create, entity, Some(details.toString)), entity, Some(entity))

    def update(entity: E with Entity, details: JsObject)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      if (details == JsObject.empty) Success(())
      else auditSrv.create(Audit(Audit.update, entity, Some(details.toString)), entity, Some(entity))

    def delete(entity: E with Entity, context: Product with Entity, details: Option[JsObject] = None)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(Audit(Audit.delete, entity, details.map(_.toString())), context, None)

    def merge(entity: E with Entity, details: Option[JsObject] = None)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(Audit(Audit.merge, entity, details.map(_.toString())), entity, Some(entity))
  }

  class UserAudit extends SelfContextObjectAudit[User] {

    def changeProfile(user: User with Entity, organisation: Organisation with Entity, profile: Profile)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, user, Some(Json.obj("organisation" -> organisation.name, "profile" -> profile.name).toString)),
        organisation,
        Some(user)
      )

    def delete(user: User with Entity, organisation: Organisation with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.delete, user, Some(Json.obj("organisation" -> organisation.name).toString)),
        organisation,
        None
      )
  }

  class ShareAudit {

    def shareCase(`case`: Case with Entity, organisation: Organisation with Entity, profile: Profile with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, `case`, Some(Json.obj("share" -> Json.obj("organisation" -> organisation.name, "profile" -> profile.name)).toString)),
        `case`,
        Some(`case`)
      )

    def shareTask(task: Task with Entity, `case`: Case with Entity, organisation: Organisation with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, task, Some(Json.obj("share" -> Json.obj("organisation" -> organisation.name)).toString)),
        task,
        Some(`case`)
      )

    def shareObservable(observable: Observable with Entity, `case`: Case with Entity, organisation: Organisation with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, observable, Some(Json.obj("share" -> Json.obj("organisation" -> organisation.name)).toString)),
        observable,
        Some(`case`)
      )

    def unshareCase(`case`: Case with Entity, organisation: Organisation with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, `case`, Some(Json.obj("unshare" -> Json.obj("organisation" -> organisation.name)).toString)),
        `case`,
        Some(`case`)
      )

    def unshareTask(task: Task with Entity, `case`: Case with Entity, organisation: Organisation with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, task, Some(Json.obj("unshare" -> Json.obj("organisation" -> organisation.name)).toString)),
        task,
        Some(`case`)
      )

    def unshareObservable(observable: Observable with Entity, `case`: Case with Entity, organisation: Organisation with Entity)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] =
      auditSrv.create(
        Audit(Audit.update, observable, Some(Json.obj("unshare" -> Json.obj("organisation" -> organisation.name)).toString)),
        observable,
        Some(`case`)
      )
  }

  class AlertAudit extends SelfContextObjectAudit[Alert] {
    def createCase(alert: Alert with Entity, `case`: Case with Entity, details: JsObject)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] = {
      auditSrv.create(Audit(Audit.update, alert, Some(Json.obj("caseId" -> `case`._id).toString)), `case`, Some(`case`))
      val detailsWithAlert = details + ("fromAlert" -> Json.obj(
        "_id"       -> alert._id.toString,
        "type"      -> alert.`type`,
        "source"    -> alert.source,
        "sourceRef" -> alert.sourceRef
      ))
      auditSrv.create(Audit(Audit.create, `case`, Some(detailsWithAlert.toString)), `case`, Some(`case`))
    }

    def mergeToCase(alert: Alert with Entity, `case`: Case with Entity, details: JsObject)(implicit
        graph: Graph,
        authContext: AuthContext
    ): Try[Unit] = {
      auditSrv.create(Audit(Audit.update, alert, Some(Json.obj("caseId" -> `case`._id).toString)), `case`, Some(`case`))
      val detailsWithAlert = details + ("fromAlert" -> Json.obj(
        "_id"       -> alert._id.toString,
        "type"      -> alert.`type`,
        "source"    -> alert.source,
        "sourceRef" -> alert.sourceRef
      ))
      auditSrv.create(Audit(Audit.merge, `case`, Some(detailsWithAlert.toString)), `case`, Some(`case`))
    }
  }
}

object AuditOps {

  implicit class VertexDefs(traversal: Traversal[Vertex, Vertex, IdentityConverter[Vertex]]) {
    def share: Traversal.V[Share] = traversal.coalesceIdent(_.in[ShareObservable], _.in[ShareTask], _.in[ShareCase], _.identity).v[Share]
  }

  implicit class AuditedObjectOpsDefs[A](traversal: Traversal.V[A]) {
    def audits: Traversal.V[Audit]            = traversal.in[Audited].v[Audit]
    def auditsFromContext: Traversal.V[Audit] = traversal.in[AuditContext].v[Audit]
  }

  implicit class AuditOpsDefs(traversal: Traversal.V[Audit]) {
    def auditContextObjectOrganisation: Traversal[
      (Audit with Entity, Option[Map[String, Seq[Any]] with Entity], Option[Map[String, Seq[Any]] with Entity], Seq[Organisation with Entity]),
      JMap[String, Any],
      Converter[
        (Audit with Entity, Option[Map[String, Seq[Any]] with Entity], Option[Map[String, Seq[Any]] with Entity], Seq[Organisation with Entity]),
        JMap[String, Any]
      ]
    ] =
      traversal
        .project(
          _.by
            .by(_.context.entityMap.option)
            .by(_.`object`.entityMap.option)
            .by(_.organisation.dedup.fold)
        )

    def richAudit: Traversal[RichAudit, JMap[String, Any], Converter[RichAudit, JMap[String, Any]]] =
      traversal
        .filter(_.context)
        .project(
          _.by
            .by(_.`case`.entity.fold)
            .by(_.context.entity)
            .by(_.`object`.entity.fold)
        )
        .domainMap {
          case (audit, context, visibilityContext, obj) =>
            val ctx = if (context.isEmpty) visibilityContext else context.head
            RichAudit(audit, ctx, visibilityContext, obj.headOption)
        }

    def richAuditWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Audit] => Traversal[D, G, C]
    ): Traversal[(RichAudit, D), JMap[String, Any], Converter[(RichAudit, D), JMap[String, Any]]] =
      traversal
        .filter(_.context)
        .project(
          _.by
            .by(_.`case`.entity.fold)
            .by(_.context.entity.fold)
            .by(_.`object`.entity.fold)
            .by(entityRenderer)
        )
        .domainMap {
          case (audit, context, visibilityContext, obj, renderedObject) =>
            val ctx = if (context.isEmpty) visibilityContext.head else context.head
            RichAudit(audit, ctx, visibilityContext.head, obj.headOption) -> renderedObject
        }

//    def forCase(caseId: String): Traversal.V[Audit] = traversal.filter(_.`case`.hasId(caseId))

    def `case`: Traversal.V[Case] =
      traversal
        .out[AuditContext]
        .share
        .out[ShareCase]
        .v[Case]

    def organisation: Traversal.V[Organisation] =
      traversal
        .out[AuditContext]
        .coalesceIdent[Vertex](
          _.share.in[OrganisationShare],
          _.out[AlertOrganisation],
          _.unsafeHas("_label", "Organisation"),
          _.out[CaseTemplateOrganisation],
          _.in[OrganisationDashboard]
        )
        .v[Organisation]

    def organisationIds: Traversal[EntityId, AnyRef, Converter[EntityId, AnyRef]] =
      traversal
        .out[AuditContext]
        .chooseBranch[String, AnyRef](
          _.on(_.label)
            .option("Case", _.v[Case].value(_.organisationIds).widen[AnyRef])
            .option("Observable", _.v[Observable].value(_.organisationIds).widen[AnyRef])
            .option("Task", _.v[Task].value(_.organisationIds).widen[AnyRef])
            .option("Alert", _.v[Alert].value(_.organisationId).widen[AnyRef])
            .option("Organisation", _.v[Organisation]._id)
            .option("CaseTemplate", _.v[CaseTemplate].organisation._id)
            .option("Dashboard", _.v[Dashboard].organisation._id)
            .option("Share", _.v[Share].organisation._id)
        )
        .domainMap(EntityId.apply)

    def caseId: Traversal[EntityId, AnyRef, Converter[EntityId, AnyRef]] =
      traversal
        .out[AuditContext]
        .chooseBranch[String, AnyRef](
          _.on(_.label)
            .option("Case", _.v[Case]._id)
            .option("Observable", _.v[Observable].value(_.relatedId).widen[AnyRef])
            .option("Task", _.v[Task].value(_.relatedId).widen[AnyRef])
            .option("Share", _.v[Share].`case`._id)
        )
        .domainMap(EntityId.apply)

    def visible(organisationSrv: OrganisationSrv)(implicit authContext: AuthContext): Traversal.V[Audit] =
      traversal.filter(
        _.out[AuditContext].chooseBranch[String, Any](
          _.on(_.label)
            .option("Case", _.v[Case].visible(organisationSrv).widen[Any])
            .option("Observable", _.v[Observable].visible(organisationSrv).widen[Any])
            .option("Task", _.v[Task].visible(organisationSrv).widen[Any])
            .option("Alert", _.v[Alert].visible(organisationSrv).widen[Any])
            .option("Organisation", _.v[Organisation].current.widen[Any])
            .option("CaseTemplate", _.v[CaseTemplate].visible.widen[Any])
            .option("Dashboard", _.v[Dashboard].visible.widen[Any])
            .option("Share", _.v[Share].organisation.current.widen[Any])
        )
      )

    def `object`: Traversal[Vertex, Vertex, IdentityConverter[Vertex]] = traversal.out[Audited]

    def context: Traversal[Vertex, Vertex, IdentityConverter[Vertex]] = traversal.out[AuditContext]
  }

}
