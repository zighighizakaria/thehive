package org.thp.thehive.dto.v0

import play.api.libs.json.{Json, OFormat, OWrites}

import java.util.Date

case class InputDashboard(title: String, description: String, status: String, definition: String)

object InputDashboard {
  implicit val writes: OWrites[InputDashboard] = Json.writes[InputDashboard]
}

case class OutputDashboard(
    _id: String,
    id: String,
    createdBy: String,
    updatedBy: Option[String] = None,
    createdAt: Date,
    updatedAt: Option[Date] = None,
    _type: String,
    title: String,
    description: String,
    status: String,
    definition: String,
    writable: Boolean
)

object OutputDashboard {
  implicit val format: OFormat[OutputDashboard] = Json.format[OutputDashboard]
}
