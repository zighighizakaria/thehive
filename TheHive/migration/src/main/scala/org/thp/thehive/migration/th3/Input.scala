package org.thp.thehive.migration.th3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.Guice
import net.codingwell.scalaguice.ScalaModule
import org.thp.thehive.migration
import org.thp.thehive.migration.Filter
import org.thp.thehive.migration.dto._
import org.thp.thehive.migration.th3.ElasticDsl._
import org.thp.thehive.models._
import play.api.libs.json._
import play.api.{Configuration, Logger}

import java.util.{Base64, Date}
import javax.inject.{Inject, Singleton}
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success, Try}

object Input {

  def apply(configuration: Configuration)(implicit actorSystem: ActorSystem): Input =
    Guice
      .createInjector(new ScalaModule {
        override def configure(): Unit = {
          bind[Configuration].toInstance(configuration)
          bind[ActorSystem].toInstance(actorSystem)
          bind[Materializer].toInstance(Materializer(actorSystem))
          bind[ExecutionContext].toInstance(actorSystem.dispatcher)
          bind[ElasticClient].toProvider[ElasticClientProvider]
          ()
        }
      })
      .getInstance(classOf[Input])
}

@Singleton
class Input @Inject() (configuration: Configuration, elasticClient: ElasticClient, implicit val ec: ExecutionContext, implicit val mat: Materializer)
    extends migration.Input
    with Conversion {
  lazy val logger: Logger               = Logger(getClass)
  override val mainOrganisation: String = configuration.get[String]("mainOrganisation")

  implicit class SourceOfJson(source: Source[JsValue, NotUsed]) {

    def read[A: Reads: ClassTag]: Source[Try[A], NotUsed] =
      source.map(json => Try(json.as[A]))

    def readWithParent[A: Reads: ClassTag](parent: JsValue => Try[String]): Source[Try[(String, A)], NotUsed] =
      source.map(json => parent(json).flatMap(p => Try(p -> json.as[A])))
  }

  override def readAttachment(id: String): Source[ByteString, NotUsed] =
    Source.unfoldAsync(0) { chunkNumber =>
      elasticClient
        .get("data", s"${id}_$chunkNumber")
        .map { json =>
          (json \ "binary").asOpt[String].map(s => chunkNumber + 1 -> ByteString(Base64.getDecoder.decode(s)))
        }
        .recover { case _ => None }
    }

  override def listOrganisations(filter: Filter): Source[Try[InputOrganisation], NotUsed] =
    Source.single(
      Success(InputOrganisation(MetaData(mainOrganisation, "system", new Date, None, None), Organisation(mainOrganisation, mainOrganisation)))
    )

  override def countOrganisations(filter: Filter): Future[Long] = Future.successful(1)

  private def caseFilter(filter: Filter): Seq[JsObject] = {
    val dateFilter =
      if (filter.caseDateRange._1.isDefined || filter.caseDateRange._2.isDefined)
        Seq(range("createdAt", filter.caseDateRange._1, filter.caseDateRange._2))
      else Nil
    val numberFilter =
      if (filter.caseNumberRange._1.isDefined || filter.caseNumberRange._2.isDefined)
        Seq(range("caseId", filter.caseNumberRange._1, filter.caseNumberRange._2))
      else Nil
    dateFilter ++ numberFilter
  }

  override def listCases(filter: Filter): Source[Try[InputCase], NotUsed] =
    elasticClient("case", searchQuery(bool(caseFilter(filter)), "-createdAt"))
      .read[InputCase]

  override def countCases(filter: Filter): Future[Long] =
    elasticClient.count("case", searchQuery(bool(caseFilter(filter))))

  override def countCaseObservables(filter: Filter): Future[Long] =
    elasticClient.count("case_artifact", searchQuery(hasParentQuery("case", bool(caseFilter(filter)))))

  override def listCaseObservables(caseId: String): Source[Try[(String, InputObservable)], NotUsed] =
    elasticClient("case_artifact", searchQuery(hasParentQuery("case", idsQuery(caseId))))
      .readWithParent[InputObservable](json => Try((json \ "_parent").as[String]))

  override def countCaseTasks(filter: Filter): Future[Long] =
    elasticClient.count("case_task", searchQuery(hasParentQuery("case", bool(caseFilter(filter)))))

  override def listCaseTasks(caseId: String): Source[Try[(String, InputTask)], NotUsed] =
    elasticClient("case_task", searchQuery(hasParentQuery("case", idsQuery(caseId))))
      .readWithParent[InputTask](json => Try((json \ "_parent").as[String]))

  override def countCaseTaskLogs(filter: Filter): Future[Long] =
    countCaseTaskLogs(bool(caseFilter(filter)))

  override def listCaseTaskLogs(caseId: String): Source[Try[(String, InputLog)], NotUsed] =
    elasticClient(
      "case_task_log",
      searchQuery(
        bool(
          Seq(hasParentQuery("case_task", hasParentQuery("case", idsQuery(caseId)))),
          Nil,
          Seq(termQuery("status", "deleted"))
        )
      )
    )
      .readWithParent[InputLog](json => Try((json \ "_parent").as[String]))

  private def countCaseTaskLogs(caseQuery: JsObject): Future[Long] =
    elasticClient.count(
      "case_task_log",
      searchQuery(
        bool(
          Seq(hasParentQuery("case_task", hasParentQuery("case", caseQuery))),
          Nil,
          Seq(termQuery("status", "deleted"))
        )
      )
    )

  private def alertFilter(filter: Filter): JsObject = {
    val dateFilter =
      if (filter.alertDateRange._1.isDefined || filter.alertDateRange._2.isDefined)
        Seq(range("createdAt", filter.alertDateRange._1, filter.alertDateRange._2))
      else Nil
    val includeFilter = (if (filter.includeAlertTypes.nonEmpty) Seq(termsQuery("type", filter.includeAlertTypes)) else Nil) ++
      (if (filter.includeAlertSources.nonEmpty) Seq(termsQuery("source", filter.includeAlertSources)) else Nil)

    val excludeFilter = (if (filter.excludeAlertTypes.nonEmpty) Seq(termsQuery("type", filter.excludeAlertTypes)) else Nil) ++
      (if (filter.excludeAlertSources.nonEmpty) Seq(termsQuery("source", filter.excludeAlertSources)) else Nil)

    bool(dateFilter ++ includeFilter, Nil, excludeFilter)
  }

  override def listAlerts(filter: Filter): Source[Try[InputAlert], NotUsed] =
    elasticClient("alert", searchQuery(alertFilter(filter), "-createdAt"))
      .read[InputAlert]

  override def countAlerts(filter: Filter): Future[Long] =
    elasticClient.count("alert", searchQuery(alertFilter(filter)))

  override def countAlertObservables(filter: Filter): Future[Long] = Future.failed(new NotImplementedError)

  override def listAlertObservables(alertId: String): Source[Try[(String, InputObservable)], NotUsed] = {
    val dummyMetaData = MetaData("no-id", "init", new Date, None, None)
    Source
      .future(elasticClient.searchRaw("alert", searchQuery(idsQuery(alertId))))
      .via(JsonReader.select("$.hits.hits[*]._source.artifacts[*]"))
      .mapConcat { data =>
        Try(Json.parse(data.toArray[Byte]))
          .flatMap { j =>
            Try(List(alertId -> j.as(alertObservableReads(dummyMetaData))))
              .recover {
                case _ if (j \ "remoteAttachment").isDefined =>
                  logger.warn(s"Pre 2.13 file observables are ignored in MISP alert $alertId")
                  Nil
              }
          }
          .fold(error => List(Failure(error)), _.map(Success(_)))
      }
  }

  override def listUsers(filter: Filter): Source[Try[InputUser], NotUsed] =
    elasticClient("user", searchQuery(matchAll))
      .read[InputUser]

  override def countUsers(filter: Filter): Future[Long] =
    elasticClient.count("user", searchQuery(matchAll))

  override def listCustomFields(filter: Filter): Source[Try[InputCustomField], NotUsed] =
    elasticClient("dblist", searchQuery(or(termQuery("dblist", "case_metrics"), termQuery("dblist", "custom_fields"))))
      .read[InputCustomField]

  override def countCustomFields(filter: Filter): Future[Long] =
    elasticClient.count("dblist", searchQuery(or(termQuery("dblist", "case_metrics"), termQuery("dblist", "custom_fields"))))

  override def listObservableTypes(filter: Filter): Source[Try[InputObservableType], NotUsed] =
    elasticClient("dblist", searchQuery(termQuery("dblist", "list_artifactDataType")))
      .read[InputObservableType]

  override def countObservableTypes(filter: Filter): Future[Long] =
    elasticClient.count("dblist", searchQuery(termQuery("dblist", "list_artifactDataType")))

  override def listProfiles(filter: Filter): Source[Try[InputProfile], NotUsed] =
    Source.empty[Try[InputProfile]]

  override def countProfiles(filter: Filter): Future[Long] = Future.successful(0)

  override def listImpactStatus(filter: Filter): Source[Try[InputImpactStatus], NotUsed] =
    Source.empty[Try[InputImpactStatus]]

  override def countImpactStatus(filter: Filter): Future[Long] = Future.successful(0)

  override def listResolutionStatus(filter: Filter): Source[Try[InputResolutionStatus], NotUsed] =
    Source.empty[Try[InputResolutionStatus]]

  override def countResolutionStatus(filter: Filter): Future[Long] = Future.successful(0)

  override def listCaseTemplate(filter: Filter): Source[Try[InputCaseTemplate], NotUsed] =
    elasticClient("caseTemplate", searchQuery(matchAll))
      .read[InputCaseTemplate]

  override def countCaseTemplate(filter: Filter): Future[Long] =
    elasticClient.count("caseTemplate", searchQuery(matchAll))

  override def countCaseTemplateTask(filter: Filter): Future[Long] = Future.failed(new NotImplementedError)

  def listCaseTemplateTask(caseTemplateId: String): Source[Try[(String, InputTask)], NotUsed] =
    Source
      .futureSource {
        elasticClient
          .get("caseTemplate", caseTemplateId)
          .map { json =>
            val metaData = json.as[MetaData]
            val tasks    = (json \ "tasks").asOpt(Reads.seq(caseTemplateTaskReads(metaData))).getOrElse(Nil)
            Source(tasks.to[immutable.Iterable].map(t => Success(caseTemplateId -> t)))
          }
          .recover {
            case error =>
              Source.single(Failure(error))
          }
      }
      .mapMaterializedValue(_ => NotUsed)

  override def countJobs(filter: Filter): Future[Long] =
    elasticClient.count("case_artifact_job", searchQuery(hasParentQuery("case_artifact", hasParentQuery("case", bool(caseFilter(filter))))))

  override def listJobs(caseId: String): Source[Try[(String, InputJob)], NotUsed] =
    elasticClient("case_artifact_job", searchQuery(hasParentQuery("case_artifact", hasParentQuery("case", idsQuery(caseId)))))
      .readWithParent[InputJob](json => Try((json \ "_parent").as[String]))(jobReads, classTag[InputJob])

  override def countJobObservables(filter: Filter): Future[Long] = Future.failed(new NotImplementedError)

  override def listJobObservables(caseId: String): Source[Try[(String, InputObservable)], NotUsed] =
    elasticClient("case_artifact_job", searchQuery(hasParentQuery("case_artifact", hasParentQuery("case", idsQuery(caseId)))))
      .map { json =>
        Try {
          val metaData = json.as[MetaData]
          (json \ "artifacts").asOpt[Seq[JsValue]].getOrElse(Nil).map(o => Try(metaData.id -> o.as(jobObservableReads(metaData))))
        }
      }
      .mapConcat {
        case Success(o)     => o.toList
        case Failure(error) => List(Failure(error))
      }

  override def countAction(filter: Filter): Future[Long] =
    elasticClient.count("action", searchQuery(matchAll))

  override def listActions(entityIds: Seq[String]): Source[Try[(String, InputAction)], NotUsed] =
    elasticClient("action", searchQuery(termsQuery("objectId", entityIds)))
      .read[(String, InputAction)]

  private def auditFilter(filter: Filter, objectIds: String*): JsObject = {
    val dateFilter =
      if (filter.auditDateRange._1.isDefined || filter.auditDateRange._2.isDefined)
        Seq(range("createdAt", filter.auditDateRange._1, filter.auditDateRange._2))
      else Nil

    val objectIdFilter = if (objectIds.nonEmpty) Seq(termsQuery("objectId", objectIds)) else Nil

    val includeFilter = (if (filter.includeAuditActions.nonEmpty) Seq(termsQuery("operation", filter.includeAuditActions)) else Nil) ++
      (if (filter.includeAuditObjectTypes.nonEmpty) Seq(termsQuery("objectType", filter.includeAuditObjectTypes)) else Nil)

    val excludeFilter = (if (filter.excludeAuditActions.nonEmpty) Seq(termsQuery("operation", filter.excludeAuditActions)) else Nil) ++
      (if (filter.excludeAuditObjectTypes.nonEmpty) Seq(termsQuery("objectType", filter.excludeAuditObjectTypes)) else Nil)

    bool(dateFilter ++ includeFilter ++ objectIdFilter, Nil, excludeFilter)
  }

  override def countAudits(filter: Filter): Future[Long] =
    elasticClient.count("audit", searchQuery(auditFilter(filter)))

  override def listAudits(entityIds: Seq[String], filter: Filter): Source[Try[(String, InputAudit)], NotUsed] =
    elasticClient("audit", searchQuery(auditFilter(filter, entityIds: _*)))
      .read[(String, InputAudit)]

  override def countDashboards(filter: Filter): Future[Long] =
    elasticClient.count("dashboard", searchQuery(matchAll))

  override def listDashboards(filter: Filter): Source[Try[InputDashboard], NotUsed] =
    elasticClient("dashboard", searchQuery(matchAll))
      .read[InputDashboard]
}
