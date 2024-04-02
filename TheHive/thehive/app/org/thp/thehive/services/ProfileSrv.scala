package org.thp.thehive.services

import akka.actor.typed.ActorRef
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Graph, Traversal}
import org.thp.scalligraph.{BadRequestError, EntityIdOrName, EntityName}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import org.thp.thehive.services.ProfileOps._
import play.api.libs.json.JsObject

import javax.inject.{Inject, Provider, Singleton}
import scala.util.{Failure, Try}

@Singleton
class ProfileSrv @Inject() (
    auditSrv: AuditSrv,
    organisationSrvProvider: Provider[OrganisationSrv],
    integrityCheckActorProvider: Provider[ActorRef[IntegrityCheck.Request]]
)(implicit
    val db: Database
) extends VertexSrv[Profile] {
  lazy val organisationSrv: OrganisationSrv                      = organisationSrvProvider.get
  lazy val orgAdmin: Profile with Entity                         = db.roTransaction(graph => getOrFail(EntityName(Profile.orgAdmin.name))(graph)).get
  lazy val integrityCheckActor: ActorRef[IntegrityCheck.Request] = integrityCheckActorProvider.get

  override def createEntity(e: Profile)(implicit graph: Graph, authContext: AuthContext): Try[Profile with Entity] = {
    integrityCheckActor ! IntegrityCheck.EntityAdded("Profile")
    super.createEntity(e)
  }

  def create(profile: Profile)(implicit graph: Graph, authContext: AuthContext): Try[Profile with Entity] =
    for {
      createdProfile <- createEntity(profile)
      _              <- auditSrv.profile.create(createdProfile, createdProfile.toJson)
    } yield createdProfile

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[Profile] =
    startTraversal.getByName(name)

  override def exists(e: Profile)(implicit graph: Graph): Boolean = startTraversal.getByName(e.name).exists

  def remove(profile: Profile with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    if (!profile.isEditable)
      Failure(BadRequestError(s"Profile ${profile.name} cannot be removed"))
    else if (get(profile).filter(_.or(_.roles, _.shares)).exists)
      Failure(BadRequestError(s"Profile ${profile.name} is used"))
    else
      organisationSrv.getOrFail(authContext.organisation).flatMap { organisation =>
        get(profile).remove()
        auditSrv.profile.delete(profile, organisation)
      }

  override def update(
      traversal: Traversal.V[Profile],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[Profile], JsObject)] =
    if (traversal.clone().toIterator.exists(!_.isEditable))
      Failure(BadRequestError(s"Profile is not editable"))
    else super.update(traversal, propertyUpdaters)
}

object ProfileOps {

  implicit class ProfileOpsDefs(traversal: Traversal.V[Profile]) {

    def roles: Traversal.V[Role] = traversal.in[RoleProfile].v[Role]

    def shares: Traversal.V[Share] = traversal.in[ShareProfile].v[Share]

    def get(idOrName: EntityIdOrName): Traversal.V[Profile] =
      idOrName.fold(traversal.getByIds(_), getByName)

    def getByName(name: String): Traversal.V[Profile] = traversal.has(_.name, name)

    def contains(permission: Permission): Traversal.V[Profile] =
      traversal.has(_.permissions, permission)
  }

}

class ProfileIntegrityCheck @Inject() (val db: Database, val service: ProfileSrv) extends DedupCheck[Profile]
