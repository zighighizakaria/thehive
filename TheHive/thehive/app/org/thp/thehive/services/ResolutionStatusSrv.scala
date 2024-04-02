package org.thp.thehive.services

import akka.actor.typed.ActorRef
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.services.{DedupCheck, IntegrityCheckOps, VertexSrv}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Graph, Traversal}
import org.thp.scalligraph.{CreateError, EntityIdOrName}
import org.thp.thehive.models.ResolutionStatus
import org.thp.thehive.services.ResolutionStatusOps._

import javax.inject.{Inject, Provider, Singleton}
import scala.util.{Failure, Success, Try}

@Singleton
class ResolutionStatusSrv @Inject() (integrityCheckActorProvider: Provider[ActorRef[IntegrityCheck.Request]]) extends VertexSrv[ResolutionStatus] {
  lazy val integrityCheckActor: ActorRef[IntegrityCheck.Request] = integrityCheckActorProvider.get

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[ResolutionStatus] =
    startTraversal.getByName(name)

  override def createEntity(e: ResolutionStatus)(implicit graph: Graph, authContext: AuthContext): Try[ResolutionStatus with Entity] = {
    integrityCheckActor ! IntegrityCheck.EntityAdded("Resolution")
    super.createEntity(e)
  }

  def create(resolutionStatus: ResolutionStatus)(implicit graph: Graph, authContext: AuthContext): Try[ResolutionStatus with Entity] =
    if (exists(resolutionStatus))
      Failure(CreateError(s"Resolution status ${resolutionStatus.value} already exists"))
    else
      createEntity(resolutionStatus)

  override def exists(e: ResolutionStatus)(implicit graph: Graph): Boolean = startTraversal.getByName(e.value).exists
}

object ResolutionStatusOps {
  implicit class ResolutionStatusOpsDefs(traversal: Traversal.V[ResolutionStatus]) {
    def get(idOrName: EntityIdOrName): Traversal.V[ResolutionStatus] =
      idOrName.fold(traversal.getByIds(_), getByName)

    def getByName(name: String): Traversal.V[ResolutionStatus] = traversal.has(_.value, name)
  }
}

class ResolutionStatusIntegrityCheck @Inject() (val db: Database, val service: ResolutionStatusSrv) extends DedupCheck[ResolutionStatus]
