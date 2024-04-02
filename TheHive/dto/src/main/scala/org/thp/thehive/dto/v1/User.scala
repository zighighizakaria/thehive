package org.thp.thehive.dto.v1

import org.thp.scalligraph.controllers.FFile
import play.api.libs.json.{JsObject, Json, OFormat, Writes}

import java.util.Date

case class InputUser(login: String, name: String, password: Option[String], profile: String, organisation: Option[String], avatar: Option[FFile])

object InputUser {
  implicit val writes: Writes[InputUser] = Json.writes[InputUser]
}

case class OutputOrganisationProfile(organisationId: String, organisation: String, profile: String)
object OutputOrganisationProfile {
  implicit val format: OFormat[OutputOrganisationProfile] = Json.format[OutputOrganisationProfile]
}

case class OutputUser(
    _id: String,
    _createdBy: String,
    _updatedBy: Option[String],
    _createdAt: Date,
    _updatedAt: Option[Date],
    login: String,
    name: String,
    hasKey: Boolean,
    hasPassword: Boolean,
    hasMFA: Boolean,
    locked: Boolean,
    profile: String,
    permissions: Set[String],
    organisation: String,
    avatar: Option[String],
    organisations: Seq[OutputOrganisationProfile],
    extraData: JsObject
)

object OutputUser {
  implicit val format: OFormat[OutputUser] = Json.format[OutputUser]
}
