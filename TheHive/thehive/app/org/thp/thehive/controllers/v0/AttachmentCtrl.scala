package org.thp.thehive.controllers.v0

import akka.stream.scaladsl.FileIO
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.{CompressionLevel, EncryptionMethod}
import org.thp.scalligraph.controllers.Entrypoint
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.services.config.{ApplicationConfig, ConfigItem}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.{EntityIdOrName, NotFoundError}
import org.thp.thehive.controllers.HttpHeaderParameterEncoding
import org.thp.thehive.services.AttachmentOps._
import org.thp.thehive.services.AttachmentSrv
import play.api.http.HttpEntity
import play.api.mvc._

import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

@Singleton
class AttachmentCtrl @Inject() (
    entrypoint: Entrypoint,
    appConfig: ApplicationConfig,
    attachmentSrv: AttachmentSrv,
    db: Database,
    ec: ExecutionContext
) {
  val forbiddenChar: Seq[Char] = Seq('/', '\n', '\r', '\t', '\u0000', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':', ';')

  val passwordConfig: ConfigItem[String, String] = appConfig.item[String]("datastore.attachment.password", "Password used to protect attachment ZIP")

  def download(id: String, name: Option[String]): Action[AnyContent] =
    entrypoint("download attachment")
      .authRoTransaction(db) { implicit authContext => implicit graph =>
        val filename = name.getOrElse(id).map(c => if (forbiddenChar.contains(c)) '_' else c)
        attachmentSrv
          .get(EntityIdOrName(id))
          .visible
          .getOrFail("Attachment")
          .filter(attachmentSrv.exists)
          .map { attachment =>
            Result(
              header = ResponseHeader(
                200,
                Map(
                  "Content-Disposition"       -> s"""attachment; ${HttpHeaderParameterEncoding.encode("filename", filename)}""",
                  "Content-Transfer-Encoding" -> "binary"
                )
              ),
              body = HttpEntity.Streamed(attachmentSrv.source(attachment), None, None)
            )
          }
          .recoverWith {
            case _: NoSuchElementException => Failure(NotFoundError(s"Attachment $id not found"))
          }
      }

  def downloadZip(id: String, name: Option[String]): Action[AnyContent] =
    entrypoint("download attachment")
      .authRoTransaction(db) { implicit authContext => implicit graph =>
        val filename = name.getOrElse(id).map(c => if (forbiddenChar.contains(c)) '_' else c)
        attachmentSrv
          .get(EntityIdOrName(id))
          .visible
          .getOrFail("Attachment")
          .filter(attachmentSrv.exists)
          .flatMap { attachment =>
            Try {
              val f = Files.createTempFile("downloadzip-", id)
              Files.delete(f)
              val zipFile   = new ZipFile(f.toFile, password.toCharArray)
              val zipParams = new ZipParameters
              zipParams.setCompressionLevel(CompressionLevel.FASTEST)
              zipParams.setEncryptFiles(true)
              zipParams.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD)
              zipParams.setFileNameInZip(filename)
              //      zipParams.setSourceExternalStream(true)
              val is = attachmentSrv.stream(attachment)
              try zipFile.addStream(is, zipParams)
              finally is.close()
              val source = FileIO.fromPath(f).mapMaterializedValue { fut =>
                fut.andThen { case _ => Files.delete(f) }(ec)
              }
              Result(
                header = ResponseHeader(
                  200,
                  Map(
                    "Content-Disposition"       -> s"""attachment; ${HttpHeaderParameterEncoding.encode("filename", s"$filename.zip")}""",
                    "Content-Type"              -> "application/zip",
                    "Content-Transfer-Encoding" -> "binary",
                    "Content-Length"            -> Files.size(f).toString
                  )
                ),
                body = HttpEntity.Streamed(source, Some(Files.size(f)), Some("application/zip"))
              )
            }
          }
          .recoverWith {
            case _: NoSuchElementException => Failure(NotFoundError(s"Attachment $id not found"))
          }
      }

  def password: String = passwordConfig.get
}
