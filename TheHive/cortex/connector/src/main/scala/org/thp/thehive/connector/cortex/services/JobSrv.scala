package org.thp.thehive.connector.cortex.services

import akka.Done
import akka.actor._
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import com.google.inject.name.Named
import io.scalaland.chimney.dsl._
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.thp.cortex.client.CortexClient
import org.thp.cortex.dto.v0.{InputArtifact, OutputArtifact, Attachment => CortexAttachment, JobStatus => CortexJobStatus, OutputJob => CortexJob}
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.FFile
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, Graph, StepLabel, Traversal}
import org.thp.scalligraph.{CreateError, EntityId, EntityIdOrName, NotFoundError}
import org.thp.thehive.connector.cortex.controllers.v0.Conversion._
import org.thp.thehive.connector.cortex.models._
import org.thp.thehive.connector.cortex.services.Conversion._
import org.thp.thehive.connector.cortex.services.JobOps._
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.models._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.{AttachmentSrv, ObservableSrv, ObservableTypeSrv, OrganisationSrv, ReportTagSrv}
import play.api.libs.json.{JsObject, JsString, Json}

import java.nio.file.Files
import java.util.{Date, Map => JMap}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

@Singleton
class JobSrv @Inject() (
    connector: Connector,
    @Named("cortex-actor") cortexActor: ActorRef,
    observableSrv: ObservableSrv,
    observableTypeSrv: ObservableTypeSrv,
    attachmentSrv: AttachmentSrv,
    reportTagSrv: ReportTagSrv,
    actionOperationSrv: ActionOperationSrv,
    serviceHelper: ServiceHelper,
    auditSrv: CortexAuditSrv,
    organisationSrv: OrganisationSrv,
    implicit val db: Database,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer
) extends VertexSrv[Job] {

  val observableJobSrv    = new EdgeSrv[ObservableJob, Observable, Job]
  val reportObservableSrv = new EdgeSrv[ReportObservable, Job, Observable]

  /**
    * Submits an observable for analysis to cortex client and stores
    * resulting job and send the cortex reference id to the polling job status actor
    *
    * @param cortexId    the client id name
    * @param workerId    the analyzer (worker) id
    * @param observable  the observable to analyze
    * @param `case`      the related case
    * @param authContext auth context instance
    * @return
    */
  def submit(cortexId: String, workerId: String, observable: RichObservable, `case`: Case with Entity, parameters: JsObject)(implicit
      authContext: AuthContext
  ): Future[RichJob] = {
    val parametersWithRequesterInfo = db.roTransaction { implicit graph =>
      parameters +
        ("organisation" -> JsString(organisationSrv.current.value(_.name).head)) +
        ("user"         -> JsString(authContext.userId))
    }

    for {
      cortexClient <-
        serviceHelper
          .availableCortexClients(connector.clients, authContext.organisation)
          .find(_.name == cortexId)
          .fold[Future[CortexClient]](Future.failed(NotFoundError(s"Cortex $cortexId not found")))(Future.successful)
      analyzer <- cortexClient.getAnalyzer(workerId).recoverWith {
        case _ => cortexClient.getAnalyzerByName(workerId)
      } // if get analyzer using cortex2 API fails, try using legacy API
      cortexArtifact <- observable.dataOrAttachment match {
        case Left(data) =>
          Future.successful(
            InputArtifact(observable.tlp, `case`.pap, observable.dataType, `case`.number.toString, Some(data), None, parametersWithRequesterInfo)
          )
        case Right(a) =>
          val attachment = CortexAttachment(a.name, a.size, a.contentType, attachmentSrv.source(a))
          Future.successful(
            InputArtifact(
              observable.tlp,
              `case`.pap,
              observable.dataType,
              `case`.number.toString,
              None,
              Some(attachment),
              parametersWithRequesterInfo
            )
          )
        case _ => Future.failed(new Exception(s"Invalid Observable data for ${observable.observable._id}"))
      }
      cortexOutputJob <- cortexClient.analyse(analyzer.id, cortexArtifact)
      createdJob <- Future.fromTry(db.tryTransaction { implicit graph =>
        create(fromCortexOutputJob(cortexOutputJob).copy(cortexId = cortexId), observable.observable)
      })
      _ <- Future.fromTry(db.tryTransaction { implicit graph =>
        auditSrv
          .job
          .create(
            createdJob.job,
            observable.observable,
            createdJob.toJson.as[JsObject] + ("objectType" -> JsString("Observable")) + ("objectId" -> JsString(observable._id.toString))
          )
      })
      _ = cortexActor ! CheckJob(Some(createdJob._id), cortexOutputJob.id, None, cortexClient.name, authContext)
    } yield createdJob
  }

  private def fromCortexOutputJob(j: CortexJob): Job =
    j.into[Job]
      .withFieldComputed(_.workerId, _.workerId)
      .withFieldComputed(_.workerName, _.workerName)
      .withFieldComputed(_.workerDefinition, _.workerDefinition)
      .withFieldComputed(_.status, s => JobStatus.withName(s.status.toString))
      .withFieldComputed(_.startDate, j => j.startDate.getOrElse(j.date))
      .withFieldComputed(_.endDate, j => j.endDate.getOrElse(j.date))
      .withFieldConst(_.report, None)
      .withFieldConst(_.cortexId, "tbd")
      .withFieldComputed(_.cortexJobId, _.id)
      .withFieldConst(_.operations, Nil)
      .transform

  /**
    * Creates a Job with with according ObservableJob edge
    *
    * @param job         the job date to create
    * @param observable  the related observable
    * @param graph       the implicit graph instance needed
    * @param authContext the implicit auth needed
    * @return
    */
  def create(job: Job, observable: Observable with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichJob] =
    for {
      createdJob <- createEntity(job)
      _          <- observableJobSrv.create(ObservableJob(), observable, createdJob)
    } yield RichJob(createdJob, Nil)

  /**
    * Once a job has finished on Cortex side
    * the report is processed here: each report's artifacts
    * are stored as separate Observable with the appropriate edge ObservableJob
    *
    * @param cortexId     Id of cortex
    * @param jobId        the job db id
    * @param cortexJob    the CortexOutputJob
    * @param authContext  the auth context for db queries
    * @return the updated job
    */
  def finished(cortexId: String, jobId: EntityId, cortexJob: CortexJob)(implicit
      authContext: AuthContext
  ): Future[Job with Entity] =
    for {
      cortexClient <-
        serviceHelper
          .availableCortexClients(connector.clients, authContext.organisation)
          .find(_.name == cortexId)
          .fold[Future[CortexClient]](Future.failed(NotFoundError(s"Cortex $cortexId not found")))(Future.successful)
      operations <- Future.fromTry(executeOperations(jobId, cortexJob))
      job        <- Future.fromTry(updateJobStatus(jobId, cortexJob, operations))
      _          <- importCortexArtifacts(job, cortexJob, cortexClient)
      _          <- Future.fromTry(importAnalyzerTags(job, cortexJob))
    } yield job

  def executeOperations(jobId: EntityId, cortexJob: CortexJob)(implicit authContext: AuthContext): Try[Seq[ActionOperationStatus]] =
    db.tryTransaction { implicit graph =>
      get(jobId)
        .observable
        .project(_.by.by(_.`case`.option))
        .getOrFail("Observable")
        .map {
          case (relatedObservable, relatedCase) =>
            cortexJob
              .report
              .fold[Seq[ActionOperation]](Nil)(_.operations.map(_.as[ActionOperation]))
              .map { operation =>
                actionOperationSrv
                  .execute(relatedObservable, operation, relatedCase, None)
                  .fold(t => ActionOperationStatus(operation, success = false, t.getMessage), identity)
              }
        }
    }

  /**
    * Update job status, set the endDate and remove artifacts from report
    *
    * @param jobId id of the job to update
    * @param cortexJob the job from cortex
    * @param authContext the authentication context
    * @return the updated job
    */
  private def updateJobStatus(jobId: EntityId, cortexJob: CortexJob, operations: Seq[ActionOperationStatus])(implicit
      authContext: AuthContext
  ): Try[Job with Entity] =
    db.tryTransaction { implicit graph =>
      getOrFail(jobId).flatMap { job =>
        val report  = cortexJob.report.flatMap(r => r.full orElse r.errorMessage.map(m => Json.obj("errorMessage" -> m)))
        val status  = cortexJob.status.toJobStatus
        val endDate = new Date()
        for {
          job <- get(job)
            .update(_.report, report)
            .update(_.status, status)
            .update(_.endDate, endDate)
            .update(_._updatedAt, Some(new Date))
            .update(_._updatedBy, Some(authContext.userId))
            .update(_.operations, operations.map(o => Json.toJsObject(o)))
            .getOrFail("Job")
          observable <- get(job).observable.getOrFail("Observable")
          _ <-
            auditSrv
              .job
              .update(
                job,
                observable,
                Json.obj("status" -> status, "endDate" -> endDate, "objectType" -> "Observable", "objectId" -> observable._id.toString)
              )
        } yield job
      }
    }

  private def importAnalyzerTags(job: Job with Entity, cortexJob: CortexJob)(implicit authContext: AuthContext): Try[Unit] =
    if (cortexJob.status == CortexJobStatus.Success)
      db.tryTransaction { implicit graph =>
        val tags = cortexJob.report.fold[Seq[ReportTag]](Nil)(_.summary.map(_.toAnalyzerTag(job.workerName)))
        for {
          observable <- get(job).observable.getOrFail("Observable")
          _          <- reportTagSrv.updateTags(observable, tags)
        } yield ()
      }
    else Success(())

  /**
    * Create observable for each artifact of the job report
    *
    * @param job the job on which the observables will be linked
    * @param cortexJob the cortex job containing the artifact to import
    * @param cortexClient the cortex client used to download attachment observable
    * @param authContext the authentication context
    * @return
    */
  private def importCortexArtifacts(job: Job with Entity, cortexJob: CortexJob, cortexClient: CortexClient)(implicit
      authContext: AuthContext
  ): Future[Done] = {
    val artifacts = cortexJob
      .report
      .toList
      .flatMap(_.artifacts)
    Future
      .traverse(artifacts) { artifact =>
        db.tryTransaction(graph => observableTypeSrv.getOrFail(EntityIdOrName(artifact.dataType))(graph))
          .fold(
            Future.failed,
            {
              case attachmentType if attachmentType.isAttachment => importCortexAttachment(job, artifact, cortexClient)
              case _ =>
                Future
                  .fromTry {
                    db.tryTransaction { implicit graph =>
                      get(job).observable.getOrFail("Observable").map { origObs =>
                        observableSrv
                          .create(artifact.toObservable(job._id, origObs.organisationIds), artifact.data.get)
                          .foreach(obs => addObservable(job, obs.observable))
                      }
                    }
                  }
            }
          )
          .recover {
            case _: CreateError =>
            case error          => logger.error("Fail to import observable from Job", error)
          }
      }
      .map(_ => Done)
  }

  def addObservable(
      job: Job with Entity,
      observable: Observable with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[ReportObservable with Entity] =
    reportObservableSrv.create(ReportObservable(), job, observable)

  /**
    * Downloads and import the attachment file for an artifact of type file
    * from the job's report and saves the Attachment to the db
    *
    * @param cortexClient the client api
    * @param authContext  the auth context necessary for db persistence
    * @return
    */
  private def importCortexAttachment(
      job: Job with Entity,
      artifact: OutputArtifact,
      cortexClient: CortexClient
  )(implicit
      authContext: AuthContext
  ): Future[Unit] =
    artifact
      .attachment
      .map { attachment =>
        val file = Files.createTempFile(s"job-cortex-${attachment.id}", "")
        (for {
          src <- cortexClient.getAttachment(attachment.id)
          _   <- src.runWith(FileIO.toPath(file))
          fFile = FFile(attachment.name.getOrElse(attachment.id), file, attachment.contentType.getOrElse("application/octet-stream"))
          _ <- Future.fromTry {
            db.tryTransaction { implicit graph =>
              for {
                origObs           <- get(job).observable.getOrFail("Observable")
                createdAttachment <- attachmentSrv.create(fFile)
                richObservable    <- observableSrv.create(artifact.toObservable(job._id, origObs.organisationIds), createdAttachment)
                _                 <- reportObservableSrv.create(ReportObservable(), job, richObservable.observable)
              } yield ()
            }
          }
        } yield ())
          .andThen { case _ => Files.delete(file) }
      }
      .getOrElse(Future.failed(new Exception(s"Attachment not present for artifact ${artifact.dataType}")))

}

object JobOps {

  implicit class JobOpsDefs(traversal: Traversal.V[Job]) {

    /**
      * Checks if a Job is visible from a certain UserRole end
      *
      * @param authContext the auth context to check login against
      * @return
      */
    def visible(implicit authContext: AuthContext): Traversal.V[Job] =
      traversal.filter(_.observable.organisations.visible)

    /**
      * Checks if a job is accessible if the user and
      * the share profile contain the permission
      *
      * @param permission  the permission to check
      * @param authContext the user context
      * @return
      */
    def can(permission: Permission)(implicit authContext: AuthContext): Traversal.V[Job] =
      if (authContext.permissions.contains(permission))
        traversal.filter(_.observable.can(permission))
      else traversal.empty

    def observable: Traversal.V[Observable] = traversal.in[ObservableJob].v[Observable]

    /**
      * Returns the potential observables that were attached to a job report
      * after analyze has completed
      *
      * @return
      */
    def reportObservables: Traversal.V[Observable] = traversal.out[ReportObservable].v[Observable]

    def richJob(implicit authContext: AuthContext): Traversal[RichJob, JMap[String, Any], Converter[RichJob, JMap[String, Any]]] = {
      val thisJob = StepLabel.v[Job]
      traversal
        .as(thisJob)
        .project(
          _.by
            .by(
              _.reportObservables
                .project(
                  _.by(_.richObservable)
                    .by(_.similar.where(_.`case`.observables.out[ObservableJob].v[Job].isStep(P.eq[String](thisJob.name)))._id.fold)
                )
                .fold
            )
        )
        .domainMap {
          case (job, observablesWithLink) =>
            val observables = observablesWithLink.map {
              case (obs, l) => obs -> Json.obj("observableId" -> l.headOption)
            }
            RichJob(job, observables)
        }
    }

    def richJobWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Job] => Traversal[D, G, C]
    )(implicit authContext: AuthContext): Traversal[(RichJob, D), JMap[String, Any], Converter[(RichJob, D), JMap[String, Any]]] = {
      val thisJob = StepLabel.v[Job]
      traversal
        .as(thisJob)
        .project(
          _.by
            .by(
              _.reportObservables
                .project(
                  _.by(_.richObservable)
                    .by(_.similar.filter(_.`case`.observables.out[ObservableJob].where(P.eq[String](thisJob.name)))._id.fold)
                )
                .fold
            )
            .by(entityRenderer)
        )
        .domainMap {
          case (job, observablesWithLink, renderedEntity) =>
            val observables = observablesWithLink.map {
              case (obs, l) => obs -> Json.obj("observableId" -> l)
            }
            RichJob(job, observables) -> renderedEntity
        }
    }
  }
  implicit class CortexObservableOpsDefs(traversal: Traversal.V[Observable]) {
    def jobs: Traversal.V[Job] = traversal.out[ObservableJob].v[Job]
  }

}
