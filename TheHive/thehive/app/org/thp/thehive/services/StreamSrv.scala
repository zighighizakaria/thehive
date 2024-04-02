package org.thp.thehive.services

import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSystem, Cancellable, Identify, PoisonPill, Props}
import akka.pattern.{ask, AskTimeoutException}
import akka.serialization.Serializer
import akka.util.Timeout
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.services.EventSrv
import org.thp.scalligraph.services.config.ApplicationConfig.finiteDurationFormat
import org.thp.scalligraph.services.config.{ApplicationConfig, ConfigItem}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.utils.Retry
import org.thp.scalligraph.{EntityId, NotFoundError}
import org.thp.thehive.services.AuditOps._
import play.api.Logger
import play.api.libs.json.Json

import java.io.NotSerializableException
import javax.inject.{Inject, Singleton}
import scala.collection.immutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

sealed trait StreamMessage extends Serializable

object StreamTopic {
  def apply(streamId: String): String = s"stream-$streamId"
  val dispatcher: String              = "stream"
}

case class AuditStreamMessage(id: EntityId*) extends StreamMessage
/* Ask messages, wait if there is no ready messages */
case object GetStreamMessages extends StreamMessage
case object Commit            extends StreamMessage

/**
  * This actor receive message generated locally and when aggregation is finished (http request is over) send the message
  * to global stream actor.
  */
class StreamActor(
    organisationSrv: OrganisationSrv,
    authContext: AuthContext,
    refresh: FiniteDuration,
    maxWait: FiniteDuration,
    graceDuration: FiniteDuration,
    keepAlive: FiniteDuration,
    auditSrv: AuditSrv,
    db: Database
) extends Actor {
  import context.dispatcher

  lazy val logger: Logger = Logger(getClass)

  override def receive: Receive = {
    val keepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
    receive(Nil, keepAliveTimer)
  }

  def receive(messages: Seq[EntityId], keepAliveTimer: Cancellable): Receive = {
    case GetStreamMessages =>
      logger.debug(s"[$self] GetStreamMessages")
      // rearm keepalive
      keepAliveTimer.cancel()
      val newKeepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
      val commitTimer       = context.system.scheduler.scheduleOnce(refresh, self, Commit)
      val graceTimer =
        if (messages.isEmpty) None
        else Some(context.system.scheduler.scheduleOnce(graceDuration, self, Commit))
      context.become(receive(messages, sender, newKeepAliveTimer, commitTimer, graceTimer))

    case AuditStreamMessage(ids @ _*) =>
      db.roTransaction { implicit graph =>
        val visibleIds = auditSrv
          .getByIds(ids: _*)
          .visible(organisationSrv)(authContext)
          .toSeq
          .map(_._id)
        logger.debug(s"[$self] AuditStreamMessage $ids => $visibleIds")
        if (visibleIds.nonEmpty)
          context.become(receive(messages ++ visibleIds, keepAliveTimer))
      }
  }

  def receive(
      messages: Seq[EntityId],
      requestActor: ActorRef,
      keepAliveTimer: Cancellable,
      commitTimer: Cancellable,
      graceTimer: Option[Cancellable]
  ): Receive = {
    case GetStreamMessages =>
      logger.debug(s"[$self] GetStreamMessages")
      // rearm keepalive
      keepAliveTimer.cancel()
      val newKeepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
      commitTimer.cancel()
      val newCommitTimer = context.system.scheduler.scheduleOnce(refresh, self, Commit)
      graceTimer.foreach(_.cancel())
      val newGraceTimer =
        if (messages.isEmpty) None
        else Some(context.system.scheduler.scheduleOnce(graceDuration, self, Commit))
      context.become(receive(messages, sender, newKeepAliveTimer, newCommitTimer, newGraceTimer))

    case Commit =>
      logger.debug(s"[$self] Commit")
      commitTimer.cancel()
      graceTimer.foreach(_.cancel())
      requestActor ! AuditStreamMessage(messages: _*)
      context.become(receive(Nil, keepAliveTimer))

    case AuditStreamMessage(ids @ _*) =>
      db.roTransaction { implicit graph =>
        val visibleIds = auditSrv
          .getByIds(ids: _*)
          .visible(organisationSrv)(authContext)
          .toSeq
          .map(_._id)
        logger.debug(s"[$self] AuditStreamMessage $ids => $visibleIds")
        if (visibleIds.nonEmpty) {

          graceTimer.foreach(_.cancel())
          val newGraceTimer = context.system.scheduler.scheduleOnce(graceDuration, self, Commit)
          if (messages.isEmpty) {
            commitTimer.cancel()
            val newCommitTimer = context.system.scheduler.scheduleOnce(maxWait, self, Commit)
            context.become(receive(messages ++ visibleIds, requestActor, keepAliveTimer, newCommitTimer, Some(newGraceTimer)))
          } else
            context.become(receive(messages ++ visibleIds, requestActor, keepAliveTimer, commitTimer, Some(newGraceTimer)))
        }
      }
  }
}

@Singleton
class StreamSrv @Inject() (
    appConfig: ApplicationConfig,
    eventSrv: EventSrv,
    organisationSrv: OrganisationSrv,
    auditSrv: AuditSrv,
    db: Database,
    system: ActorSystem,
    implicit val ec: ExecutionContext
) {

  lazy val logger: Logger                      = Logger(getClass)
  val streamLength                             = 20
  val alphanumeric: immutable.IndexedSeq[Char] = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')

  val refreshConfig: ConfigItem[FiniteDuration, FiniteDuration] =
    appConfig.item[FiniteDuration]("stream.longPolling.refresh", "Response time when there is no message")
  def refresh: FiniteDuration = refreshConfig.get

  val maxWaitConfig: ConfigItem[FiniteDuration, FiniteDuration] =
    appConfig.item[FiniteDuration]("stream.longPolling.maxWait", "Maximum latency when a message is ready to send")
  def maxWait: FiniteDuration = maxWaitConfig.get

  val graceDurationConfig: ConfigItem[FiniteDuration, FiniteDuration] = appConfig
    .item[FiniteDuration]("stream.longPolling.graceDuration", "When a message is ready to send, wait this time to include potential other messages")
  def graceDuration: FiniteDuration = graceDurationConfig.get

  val keepAliveConfig: ConfigItem[FiniteDuration, FiniteDuration] =
    appConfig.item[FiniteDuration]("stream.longPolling.keepAlive", "Remove the stream after this time of inactivity")
  val keepAlive: FiniteDuration = keepAliveConfig.get

  val maxAttemptsConfig: ConfigItem[Int, Int] =
    appConfig.item[Int]("stream.get.maxAttempts", "How many attempts to get stream")
  def maxAttempts: Int = maxAttemptsConfig.get

  val minBackoffConfig: ConfigItem[FiniteDuration, FiniteDuration] =
    appConfig.item[FiniteDuration]("stream.get.minBackoff", "Minimum backoff for get stream attempts")
  def minBackoff: FiniteDuration = minBackoffConfig.get

  val maxBackoffConfig: ConfigItem[FiniteDuration, FiniteDuration] =
    appConfig.item[FiniteDuration]("stream.get.maxBackoff", "Maximum backoff for get stream attempts")
  def maxBackoff: FiniteDuration = maxBackoffConfig.get

  val randomFactorConfig: ConfigItem[Double, Double] =
    appConfig.item[Double]("stream.get.randomFactor", "Random factor for stream attempts backoff")
  def randomFactor: Double = randomFactorConfig.get

  def generateStreamId(): String = Seq.fill(streamLength)(alphanumeric(Random.nextInt(alphanumeric.size))).mkString

  def isValidStreamId(streamId: String): Boolean = streamId.length == streamLength && streamId.forall(alphanumeric.contains)

  def create(implicit authContext: AuthContext): String = {
    val streamId = generateStreamId()
    val streamActor =
      system.actorOf(
        Props(classOf[StreamActor], organisationSrv, authContext, refresh, maxWait, graceDuration, keepAlive, auditSrv, db),
        s"stream-$streamId"
      )
    logger.debug(s"Register stream actor ${streamActor.path}")
    eventSrv.subscribe(StreamTopic(streamId), streamActor)
    eventSrv.subscribe(StreamTopic.dispatcher, streamActor)
    streamId
  }

  def get(streamId: String): Future[Seq[EntityId]] = {
    implicit val timeout: Timeout = Timeout(refresh + 1.second)
    Retry(maxAttempts).withBackoff(minBackoff, maxBackoff, randomFactor)(system.scheduler, system.dispatcher) {
      // Check if stream actor exists
      eventSrv
        .publishAsk(StreamTopic(streamId))(Identify(1))(Timeout(2.seconds))
        //      .ask(s"/user/stream-$streamId", Identify(1))(Timeout(2.seconds))
        .flatMap {
          case ActorIdentity(1, Some(streamActor)) =>
            logger.debug(s"Stream actor found for stream $streamId")
            (streamActor ? GetStreamMessages)
              .map {
                case AuditStreamMessage(ids @ _*) => ids
                case _                            => Nil
              }
          case other => Future.failed(NotFoundError(s"Stream $streamId doesn't exist: $other"))
        }
        .recoverWith {
          case _: AskTimeoutException => Future.failed(NotFoundError(s"Stream $streamId doesn't exist"))
        }
    }
  }
}

class StreamSerializer extends Serializer {

  def identifier: Int = 226591535
  def includeManifest = false

  /**
    * Serializes the given object into an Array of Byte
    */
  def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case AuditStreamMessage(ids @ _*) => Json.toJson(ids).toString.getBytes
      case GetStreamMessages            => "GetStreamMessages".getBytes
      case Commit                       => "Commit".getBytes
      case _                            => Array.empty[Byte] // Not serializable
    }

  /**
    * Produces an object from an array of bytes, with an optional type-hint;
    * the class should be loaded using ActorSystem.dynamicAccess.
    */
  @throws(classOf[NotSerializableException])
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
    new String(bytes) match {
      case "GetStreamMessages" => GetStreamMessages
      case "Commit"            => Commit
      case s                   => Try(AuditStreamMessage(Json.parse(s).as[Seq[String]].map(EntityId.read): _*)).getOrElse(throw new NotSerializableException)
    }
}
