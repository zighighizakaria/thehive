package org.thp.thehive.services.notification.notifiers

import org.thp.scalligraph.models.{Entity, Schema}
import org.thp.scalligraph.services.config.{ApplicationConfig, ConfigItem}
import org.thp.scalligraph.traversal.Graph
import org.thp.thehive.models.{Audit, Organisation, User}
import play.api.Configuration

import java.nio.charset.Charset
import java.nio.file.{Files, Paths, StandardOpenOption}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class AppendToFileProvider @Inject() (appConfig: ApplicationConfig, schema: Schema, ec: ExecutionContext) extends NotifierProvider {
  override val name: String = "AppendToFile"

  val templateConfig: ConfigItem[String, String] =
    appConfig.item[String]("notification.appendToFile.template", "Template text to append")

  val baseUrlConfig: ConfigItem[String, String] =
    appConfig.item[String]("application.baseUrl", "Application base URL")

  override def apply(config: Configuration): Try[Notifier] =
    config.getOrFail[String]("file").map { filename =>
      val template = config.getOptional[String]("message").getOrElse(templateConfig.get)
      val charset  = config.getOptional[String]("charset").fold(Charset.defaultCharset())(Charset.forName)
      new AppendToFile(filename, template, charset, baseUrlConfig.get, schema, ec)
    }
}

class AppendToFile(filename: String, template: String, charset: Charset, baseUrl: String, val schema: Schema, implicit val ec: ExecutionContext)
    extends Notifier
    with Template {
  override val name: String = "AppendToFile"

  override def execute(
      audit: Audit with Entity,
      context: Option[Map[String, Seq[Any]] with Entity],
      `object`: Option[Map[String, Seq[Any]] with Entity],
      organisation: Organisation with Entity,
      user: Option[User with Entity]
  )(implicit
      graph: Graph
  ): Future[Unit] =
    buildMessage(template, audit, context, `object`, user, baseUrl).fold(
      Future.failed[Unit],
      message =>
        Future(Files.write(Paths.get(filename), message.getBytes(charset), StandardOpenOption.APPEND, StandardOpenOption.CREATE)).map(_ => ())
    )
}
