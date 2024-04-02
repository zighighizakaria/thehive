package org.thp.thehive.controllers.v0

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.controllers.Entrypoint
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.models.Permissions
import play.api.mvc.{Action, AnyContent, Results}

import scala.util.Success

@Singleton
class PermissionCtrl @Inject() (entrypoint: Entrypoint) {
  def list: Action[AnyContent] =
    entrypoint("list permissions")
      .auth(_ => Success(Results.Ok(Permissions.list.toJson)))
}
