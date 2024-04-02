package org.thp.thehive.services.notification.notifiers

import akka.stream.Materializer
import org.thp.client.{ProxyWS, ProxyWSConfig}
import org.thp.scalligraph.models.{Entity, Schema}
import org.thp.scalligraph.services.config.{ApplicationConfig, ConfigItem}
import org.thp.scalligraph.traversal.Graph
import org.thp.thehive.models.{Audit, Organisation, User}
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

case class MattermostNotification(text: String, url: String, channel: Option[String], username: Option[String])

object MattermostNotification {
  implicit val writes: Writes[MattermostNotification] = Json.writes[MattermostNotification]
}

@Singleton
class MattermostProvider @Inject() (appConfig: ApplicationConfig, ec: ExecutionContext, schema: Schema, mat: Materializer) extends NotifierProvider {
  override val name: String                            = "Mattermost"
  implicit val optionStringRead: Reads[Option[String]] = Reads.optionNoError[String]

  val webhookConfig: ConfigItem[String, String] =
    appConfig.item[String]("notification.mattermost.webhook", "Webhook url declared on Mattermost side")

  val usernameConfig: ConfigItem[Option[String], Option[String]] =
    appConfig.item[Option[String]]("notification.mattermost.username", "Username who send Mattermost message")

  val templateConfig: ConfigItem[String, String] =
    appConfig.item[String]("notification.mattermost.template", "Template message to send")

  val baseUrlConfig: ConfigItem[String, String] =
    appConfig.item[String]("application.baseUrl", "Application base URL")

  val wsConfig: ConfigItem[ProxyWSConfig, ProxyWSConfig] =
    appConfig.item[ProxyWSConfig]("notification.mattermost.ws", "HTTP client configuration")

  override def apply(config: Configuration): Try[Notifier] = {
    val template         = config.getOptional[String]("message").getOrElse(templateConfig.get)
    val channel          = config.getOptional[String]("channel")
    val usernameOverride = usernameConfig.get
    val webhook          = webhookConfig.get
    val mattermost =
      new Mattermost(
        new ProxyWS(wsConfig.get, mat),
        MattermostNotification(template, webhook, channel, usernameOverride),
        baseUrlConfig.get,
        schema,
        ec
      )
    Success(mattermost)
  }
}

class Mattermost(ws: WSClient, mattermostNotification: MattermostNotification, baseUrl: String, val schema: Schema, implicit val ec: ExecutionContext)
    extends Notifier
    with Template {
  lazy val logger: Logger   = Logger(getClass)
  override val name: String = "Mattermost"

  def execute(
      audit: Audit with Entity,
      context: Option[Map[String, Seq[Any]] with Entity],
      `object`: Option[Map[String, Seq[Any]] with Entity],
      organisation: Organisation with Entity,
      user: Option[User with Entity]
  )(implicit
      graph: Graph
  ): Future[Unit] =
    for {
      finalMessage <- Future.fromTry(buildMessage(mattermostNotification.text, audit, context, `object`, user, baseUrl))
      _ <-
        ws
          .url(mattermostNotification.url)
          .post(Json.toJson(mattermostNotification.copy(text = finalMessage)))
    } yield ()
}
