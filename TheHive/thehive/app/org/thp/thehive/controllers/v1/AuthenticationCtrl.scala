package org.thp.thehive.controllers.v1

import org.thp.scalligraph.auth.{AuthSrv, RequestOrganisation}
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.{AuthenticationError, AuthorizationError, BadRequestError, EntityIdOrName, MultiFactorCodeRequired}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models.Permissions
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.UserOps._
import org.thp.thehive.services.{TOTPAuthSrv, UserSrv}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

@Singleton
class AuthenticationCtrl @Inject() (
    entrypoint: Entrypoint,
    authSrv: AuthSrv,
    requestOrganisation: RequestOrganisation,
    userSrv: UserSrv,
    implicit val db: Database
) {

  def login: Action[AnyContent] =
    entrypoint("login")
      .extract("login", FieldsParser[String].on("user"))
      .extract("password", FieldsParser[String].on("password"))
      .extract("organisation", FieldsParser[String].optional.on("organisation"))
      .extract("code", FieldsParser[String].optional.on("code")) { implicit request =>
        val login: String                        = request.body("login")
        val password: String                     = request.body("password")
        val organisation: Option[EntityIdOrName] = request.body("organisation").map(EntityIdOrName(_)) orElse requestOrganisation(request)
        val code: Option[String]                 = request.body("code")
        for {
          authContext <- authSrv.authenticate(login, password, organisation, code)
          user <- db.roTransaction { implicit graph =>
            userSrv
              .current(graph, authContext)
              .richUserWithCustomRenderer(authContext.organisation, _.organisationWithRole)(authContext)
              .getOrFail("User")
          }
          _ <- if (user._1.locked) Failure(AuthorizationError("Your account is locked")) else Success(())
        } yield authSrv.setSessionUser(authContext)(Results.Ok(user.toJson))
      }

  def logout: Action[AnyContent] =
    entrypoint("logout") { _ =>
      Success(Results.Ok.withNewSession)
    }

  def withTotpAuthSrv[A](body: TOTPAuthSrv => Try[A]): Try[A] =
    authSrv match {
      case totpAuthSrv: TOTPAuthSrv if totpAuthSrv.enabled => body(totpAuthSrv)
      case _                                               => Failure(AuthenticationError("Operation not supported"))
    }

  def totpSetSecret: Action[AnyContent] =
    entrypoint("Set TOTP secret")
      .extract("code", FieldsParser[Int].optional.on("code"))
      .authTransaction(db) { implicit request => implicit graph =>
        withTotpAuthSrv { totpAuthSrv =>
          totpAuthSrv.getSecret(request.userId) match {
            case Some(_) => Failure(BadRequestError("TOTP is already configured"))
            case None =>
              request.session.get("totpSecret") match {
                case None =>
                  val secret = totpAuthSrv.generateSecret()
                  Success(
                    Results
                      .Ok(Json.obj("secret" -> secret, "uri" -> totpAuthSrv.getSecretURI(request.userId, secret).toString))
                      .withSession("totpSecret" -> secret)
                  )
                case Some(secret) =>
                  val code: Option[Int] = request.body("code")
                  code match {
                    case Some(c) if totpAuthSrv.codeIsValid(secret, c) => totpAuthSrv.setSecret(request.userId, secret).map(_ => Results.NoContent)
                    case Some(_)                                       => Failure(AuthenticationError("MFA code is invalid"))
                    case None                                          => Failure(MultiFactorCodeRequired("MFA code is required"))
                  }
              }
          }
        }
      }

  def totpUnsetSecret(userId: Option[String]): Action[AnyContent] =
    entrypoint("Unset TOTP secret")
      .authTransaction(db) { implicit request => implicit graph =>
        withTotpAuthSrv { totpAuthSrv =>
          userSrv
            .getOrFail(EntityIdOrName(userId.getOrElse(request.userId)))
            .flatMap { user =>
              if (request.userId == user.login || userSrv.current.organisations(Permissions.manageUser).users.getEntity(user).exists)
                totpAuthSrv.unsetSecret(user.login)
              else Failure(AuthorizationError("You cannot unset TOTP secret of this user"))
            }
            .map(_ => Results.NoContent)
        }
      }
}
